package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.rule;
import static com.felipe.ballast.recurrences.RecurrencesApi.with;
import static com.felipe.ballast.recurrences.RecurrencesApi.withRule;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Recorrência em conta de cartão (ADR-031): a ocorrência fica na data gerada (chave da regra e
// competência) e a caixa vai para o vencimento da fatura. Hoje fixo em 2026-10-19.
@IntegrationTest
class RecurrenceCardTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  @Autowired RecurrenceJob job;
  private RecurrencesApi api;
  private RecurrenceTestSupport db;
  private RecurrencesApi.Actor actor;
  private String cardId;

  @BeforeEach
  void setUp() throws Exception {
    api = new RecurrencesApi(mvc);
    db = new RecurrenceTestSupport(jdbc);
    actor = api.newActor();
    // Fecha dia 15 e vence dia 25.
    cardId = api.newCard(actor.session(), "Cartão " + UUID.randomUUID(), actor.accountId(), 15, 25);
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private static LocalDate d(String iso) {
    return LocalDate.parse(iso);
  }

  private record Row(LocalDate occurrence, LocalDate competence, LocalDate cash) {}

  private List<Row> rows(String templateId) {
    return jdbc.sql(
            "SELECT ocorrencia_data, data_competencia, data_caixa FROM lancamento"
                + " WHERE template_id = :id ORDER BY ocorrencia_data, parcela_ordem")
        .param("id", UUID.fromString(templateId))
        .query(
            (rs, i) ->
                new Row(
                    rs.getObject("ocorrencia_data", LocalDate.class),
                    rs.getObject("data_competencia", LocalDate.class),
                    rs.getObject("data_caixa", LocalDate.class)))
        .list();
  }

  private Map<String, Object> monthlyOnCard(int dayOfMonth) {
    Map<String, Object> body = with(monthlyBody(actor), "contaId", cardId);
    return withRule(body, rule("mensal", Map.of("diaDoMes", dayOfMonth, "inicio", "2026-01-01")));
  }

  private String createId(Map<String, Object> body) throws Exception {
    return api.createOk(actor.session(), body).get("recorrencia").get("id").asString();
  }

  @Test
  void monthlyOccurrencesOnACardPutTheCashOnTheDueDate() throws Exception {
    String id = createId(monthlyOnCard(20));

    List<Row> rows = rows(id);

    assertThat(rows).hasSize(12);
    // Compra dia 20 depois do fechamento do dia 15: entra na fatura do mês seguinte, que vence dia
    // 25.
    assertThat(rows.getFirst())
        .isEqualTo(new Row(d("2026-10-20"), d("2026-10-20"), d("2026-11-25")));
    assertThat(rows.get(2)).isEqualTo(new Row(d("2026-12-20"), d("2026-12-20"), d("2027-01-25")));
    assertThat(rows.getLast())
        .isEqualTo(new Row(d("2027-09-20"), d("2027-09-20"), d("2027-10-25")));
  }

  @Test
  void aDayBeforeTheClosingGoesToTheInvoiceOfTheSameMonth() throws Exception {
    String id = createId(monthlyOnCard(10));

    List<Row> rows = rows(id);

    assertThat(rows.getFirst())
        .isEqualTo(new Row(d("2026-11-10"), d("2026-11-10"), d("2026-11-25")));
  }

  @Test
  void theHorizonAndTheKeyStillFollowTheGeneratedDate() throws Exception {
    String id = createId(monthlyOnCard(20));

    assertThat(db.occurrenceDates(id).getFirst()).isEqualTo(d("2026-10-20"));
    assertThat(db.occurrenceDates(id).getLast()).isEqualTo(d("2027-09-20"));
  }

  @Test
  void theJobRunTwiceDoesNotDuplicateAndAddsTheNewMonthWithTheDueCash() throws Exception {
    String id = createId(monthlyOnCard(20));
    clock.advance(Duration.ofDays(31));

    job.run();
    long afterFirst = db.countOccurrences(id);
    job.run();

    assertThat(db.countOccurrences(id)).isEqualTo(afterFirst).isEqualTo(13);
    assertThat(rows(id).getLast())
        .isEqualTo(new Row(d("2027-10-20"), d("2027-10-20"), d("2027-11-25")));
  }

  @Test
  void theOccurrenceFromACheckingAccountKeepsBothDatesEqual() throws Exception {
    String id = createId(with(monthlyOnCard(20), "contaId", actor.accountId()));

    assertThat(rows(id).getFirst())
        .isEqualTo(new Row(d("2026-10-20"), d("2026-10-20"), d("2026-10-20")));
  }

  @Test
  void aDivisionOnACardAppliesTheRuleToEachPart() throws Exception {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("diaDoMes", 10);
    first.put("percentualCentesimos", 5000);
    Map<String, Object> second = new LinkedHashMap<>();
    second.put("diaDoMes", 20);
    second.put("percentualCentesimos", 5000);
    Map<String, Object> division = new LinkedHashMap<>();
    division.put("modo", "percentual");
    division.put("parcelas", List.of(first, second));
    Map<String, Object> body =
        with(
            withRule(
                with(monthlyBody(actor), "contaId", cardId),
                rule("mensal", Map.of("inicio", "2026-01-01"))),
            "divisao",
            division);

    String id = createId(body);

    List<Row> rows = rows(id);
    // 2026-10-10 é antes de hoje e não entra; 10/20 de novembro caem em faturas diferentes.
    assertThat(rows.getFirst())
        .isEqualTo(new Row(d("2026-10-20"), d("2026-10-20"), d("2026-11-25")));
    assertThat(rows.get(1)).isEqualTo(new Row(d("2026-11-10"), d("2026-11-10"), d("2026-11-25")));
    assertThat(rows.get(2)).isEqualTo(new Row(d("2026-11-20"), d("2026-11-20"), d("2026-12-28")));
  }

  @Test
  void userACannotCreateARecurrenceOnUserBsCard() throws Exception {
    RecurrencesApi.Actor b = api.newActor();
    String cardOfB = api.newCard(b.session(), "Do B", b.accountId(), 15, 25);

    MockHttpServletResponse response =
        api.create(actor.session(), with(monthlyBody(actor), "contaId", cardOfB));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(db.countTemplates(actor.user().id())).isZero();
    assertThat(db.countEntries(actor.user().id())).isZero();
  }
}
