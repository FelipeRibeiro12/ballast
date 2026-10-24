package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.rule;
import static com.felipe.ballast.recurrences.RecurrencesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import tools.jackson.databind.JsonNode;

@IntegrationTest
class RecurrenceEditTest {

  private static final String NOT_FOUND = "{\"erro\":\"não encontrado\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private RecurrencesApi api;
  private RecurrenceTestSupport db;
  private RecurrencesApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new RecurrencesApi(mvc);
    db = new RecurrenceTestSupport(jdbc);
    actor = api.newActor();
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private static LocalDate d(String iso) {
    return LocalDate.parse(iso);
  }

  private String createId(Map<String, Object> body) throws Exception {
    return api.createOk(actor.session(), body).get("recorrencia").get("id").asString();
  }

  private BigDecimal amountAt(String templateId, LocalDate key) {
    return jdbc.sql(
            "SELECT valor FROM lancamento WHERE template_id = :id AND ocorrencia_data = :key")
        .param("id", UUID.fromString(templateId))
        .param("key", key)
        .query(BigDecimal.class)
        .single();
  }

  @Test
  void editingRegeneratesOnlyTheFutureUntouchedPlannedOnes() throws Exception {
    String id = createId(monthlyBody(actor));
    // Antes de hoje: uma realizada e uma prevista vencida. Daqui para frente: uma editada à mão
    // e uma cancelada. O resto é prevista gerada pela regra.
    db.insertOccurrence(id, d("2026-09-05"), "realizado", true);
    db.insertOccurrence(id, d("2026-10-05"), "previsto", false);
    jdbc.sql(
            "UPDATE lancamento SET desgarrado = true, titulo = 'Aluguel de novembro'"
                + " WHERE template_id = :id AND ocorrencia_data = :key")
        .param("id", UUID.fromString(id))
        .param("key", d("2026-11-05"))
        .update();
    jdbc.sql(
            "UPDATE lancamento SET status = 'cancelado', desgarrado = true"
                + " WHERE template_id = :id AND ocorrencia_data = :key")
        .param("id", UUID.fromString(id))
        .param("key", d("2026-12-05"))
        .update();

    MockHttpServletResponse updated =
        api.update(
            actor.session(),
            id,
            with(with(monthlyBody(actor), "valorCentavos", 180_000), "titulo", "Aluguel novo"));

    assertThat(updated.getStatus()).isEqualTo(200);
    JsonNode body = RecurrencesApi.body(updated);
    assertThat(body.get("recorrencia").get("titulo").asString()).isEqualTo("Aluguel novo");
    assertThat(body.get("recorrencia").get("valorCentavos").asLong()).isEqualTo(180_000);
    assertThat(body.get("ocorrenciasMaterializadas").asInt()).isEqualTo(10);
    assertThat(amountAt(id, d("2026-09-05"))).isEqualByComparingTo("1500.00");
    assertThat(amountAt(id, d("2026-10-05"))).isEqualByComparingTo("1500.00");
    assertThat(db.byKey(id, d("2026-11-05")).orElseThrow().title())
        .isEqualTo("Aluguel de novembro");
    assertThat(db.byKey(id, d("2026-12-05")).orElseThrow().status()).isEqualTo("cancelado");
    assertThat(amountAt(id, d("2027-01-05"))).isEqualByComparingTo("1800.00");
    assertThat(db.byKey(id, d("2027-01-05")).orElseThrow().title()).isEqualTo("Aluguel novo");
  }

  @Test
  void changingTheRuleMovesTheFutureOccurrences() throws Exception {
    String id = createId(monthlyBody(actor));

    api.update(
        actor.session(),
        id,
        RecurrencesApi.withRule(
            monthlyBody(actor), rule("mensal", Map.of("diaDoMes", 20, "inicio", "2026-01-01"))));

    List<LocalDate> dates = db.occurrenceDates(id);
    assertThat(dates).first().isEqualTo(d("2026-10-20"));
    assertThat(dates).allMatch(date -> date.getDayOfMonth() == 20);
  }

  @Test
  void movingToAnotherAccountRegeneratesThereAndKeepsTheRealizedOneHere() throws Exception {
    String id = createId(monthlyBody(actor));
    db.insertOccurrence(id, d("2026-09-05"), "realizado", true);
    String savings = api.newAccount(actor.session(), "Poupança " + UUID.randomUUID());

    MockHttpServletResponse updated =
        api.update(actor.session(), id, with(monthlyBody(actor), "contaId", savings));

    assertThat(updated.getStatus()).isEqualTo(200);
    long elsewhere =
        jdbc.sql(
                "SELECT count(*) FROM lancamento WHERE template_id = :id AND conta_id = :account"
                    + " AND status = 'previsto'")
            .param("id", UUID.fromString(id))
            .param("account", UUID.fromString(savings))
            .query(Long.class)
            .single();
    assertThat(elsewhere).isEqualTo(12);
    assertThat(db.countByStatus(id, "realizado")).isEqualTo(1);
  }

  @Test
  void theTypeDoesNotChange() throws Exception {
    String id = createId(monthlyBody(actor));

    MockHttpServletResponse refused =
        api.update(
            actor.session(),
            id,
            with(
                with(monthlyBody(actor), "tipo", "receita"),
                "categoriaId",
                actor.incomeCategoryId()));

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(refused))
        .isEqualTo("{\"erro\":\"o tipo da recorrência não muda\"}");
  }

  @Test
  void editingRefusesBadReferencesAndKeepsEverything() throws Exception {
    String id = createId(monthlyBody(actor));
    long before = db.countOccurrences(id);
    RecurrencesApi.Actor other = api.newActor();

    MockHttpServletResponse account =
        api.update(actor.session(), id, with(monthlyBody(actor), "contaId", other.accountId()));
    MockHttpServletResponse category =
        api.update(
            actor.session(), id, with(monthlyBody(actor), "categoriaId", actor.incomeCategoryId()));

    assertThat(RecurrencesApi.text(account)).isEqualTo("{\"erro\":\"conta não encontrada\"}");
    assertThat(RecurrencesApi.text(category)).isEqualTo("{\"erro\":\"categoria não encontrada\"}");
    assertThat(db.countOccurrences(id)).isEqualTo(before);
    assertThat(amountAt(id, d("2027-01-05"))).isEqualByComparingTo("1500.00");
  }

  @Test
  void editingAnInactiveRecurrenceGeneratesNothing() throws Exception {
    String id = createId(monthlyBody(actor));
    api.deactivate(actor.session(), id);

    JsonNode body =
        RecurrencesApi.body(
            api.update(actor.session(), id, with(monthlyBody(actor), "valorCentavos", 9_900)));

    assertThat(body.get("ocorrenciasMaterializadas").asInt()).isZero();
    assertThat(body.get("recorrencia").get("ativo").asBoolean()).isFalse();
    assertThat(body.get("recorrencia").get("valorCentavos").asLong()).isEqualTo(9_900);
  }

  @Test
  void reactivatingGeneratesFromTodayAgain() throws Exception {
    String id = createId(monthlyBody(actor));
    api.deactivate(actor.session(), id);
    assertThat(db.countOccurrences(id)).isZero();

    MockHttpServletResponse reactivated = api.setActive(actor.session(), id, "{\"ativo\":true}");

    assertThat(reactivated.getStatus()).isEqualTo(200);
    assertThat(RecurrencesApi.body(reactivated).get("ativo").asBoolean()).isTrue();
    assertThat(db.occurrenceDates(id)).first().isEqualTo(d("2026-11-05"));
    assertThat(db.countOccurrences(id)).isEqualTo(12);
    assertThat(api.setActive(actor.session(), id, "{\"ativo\":true}").getStatus())
        .as("repetível")
        .isEqualTo(200);
    assertThat(db.countOccurrences(id)).isEqualTo(12);
  }

  @Test
  void reactivatingInAnArchivedAccountIsRefused() throws Exception {
    String id = createId(monthlyBody(actor));
    api.deactivate(actor.session(), id);
    api.setAccountArchived(actor.session(), actor.accountId(), true);

    MockHttpServletResponse refused = api.setActive(actor.session(), id, "{\"ativo\":true}");

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(refused)).isEqualTo("{\"erro\":\"conta arquivada\"}");
  }

  @Test
  void editingAnInactiveRecurrenceOfAnArchivedAccountIsAllowed() throws Exception {
    String id = createId(monthlyBody(actor));
    api.deactivate(actor.session(), id);
    api.setAccountArchived(actor.session(), actor.accountId(), true);

    MockHttpServletResponse edited =
        api.update(actor.session(), id, with(monthlyBody(actor), "titulo", "Novo nome"));

    assertThat(edited.getStatus()).isEqualTo(200);
    assertThat(RecurrencesApi.body(edited).get("recorrencia").get("titulo").asString())
        .isEqualTo("Novo nome");
    assertThat(db.countOccurrences(id)).isZero();
    assertThat(api.setActive(actor.session(), id, "{\"ativo\":true}").getStatus())
        .as("ativar continua recusando")
        .isEqualTo(400);
  }

  @Test
  void editingAnActiveRecurrenceOfAnArchivedAccountIsStillRefused() throws Exception {
    String id = createId(monthlyBody(actor));
    api.setAccountArchived(actor.session(), actor.accountId(), true);

    MockHttpServletResponse refused =
        api.update(actor.session(), id, with(monthlyBody(actor), "titulo", "Novo nome"));

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(refused)).isEqualTo("{\"erro\":\"conta arquivada\"}");
  }

  @Test
  void deletingRemovesThePlannedOnesAndKeepsTheRealizedAsPlainEntries() throws Exception {
    String id = createId(monthlyBody(actor));
    db.insertOccurrence(id, d("2026-09-05"), "realizado", true);
    db.insertOccurrence(id, d("2026-08-05"), "cancelado", true);
    db.insertOccurrence(id, d("2026-10-05"), "previsto", false);

    MockHttpServletResponse deleted = api.remove(actor.session(), id);

    assertThat(deleted.getStatus()).isEqualTo(204);
    assertThat(api.find(actor.session(), id).getStatus()).isEqualTo(404);
    assertThat(db.countTemplates(actor.user().id())).isZero();
    assertThat(db.countRules(actor.user().id())).isZero();
    assertThat(db.countEntries(actor.user().id())).isEqualTo(2);
    long attached =
        jdbc.sql(
                "SELECT count(*) FROM lancamento WHERE usuario_id = :id AND template_id IS NOT NULL")
            .param("id", actor.user().id())
            .query(Long.class)
            .single();
    assertThat(attached).isZero();
  }

  @Test
  void deletingARecurrenceWithDivisionRemovesTheDivisionToo() throws Exception {
    Map<String, Object> body =
        with(
            RecurrencesApi.withRule(
                monthlyBody(actor), rule("mensal", Map.of("inicio", "2026-01-01"))),
            "divisao",
            Map.of(
                "modo",
                "percentual",
                "parcelas",
                List.of(
                    Map.of("diaDoMes", 5, "percentualCentesimos", 4000),
                    Map.of("diaDoMes", 20, "percentualCentesimos", 6000))));
    String id = createId(body);

    assertThat(api.remove(actor.session(), id).getStatus()).isEqualTo(204);

    long divisions =
        jdbc.sql("SELECT count(*) FROM template_divisao WHERE usuario_id = :id")
            .param("id", actor.user().id())
            .query(Long.class)
            .single();
    assertThat(divisions).isZero();
  }

  @Test
  void aRecurrenceOfAnotherUserMissingOrMalformedIs404() throws Exception {
    String id = createId(monthlyBody(actor));
    RecurrencesApi.Actor other = api.newActor();
    for (String target : List.of(id, UUID.randomUUID().toString(), "nao-e-uuid")) {
      assertThat(RecurrencesApi.text(api.remove(other.session(), target))).isEqualTo(NOT_FOUND);
      assertThat(RecurrencesApi.text(api.update(other.session(), target, monthlyBody(other))))
          .isEqualTo(NOT_FOUND);
      assertThat(RecurrencesApi.text(api.setActive(other.session(), target, "{\"ativo\":true}")))
          .isEqualTo(NOT_FOUND);
    }
    assertThat(db.countTemplates(actor.user().id())).isEqualTo(1);
  }
}
