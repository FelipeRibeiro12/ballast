package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.rule;
import static com.felipe.ballast.recurrences.RecurrencesApi.with;
import static com.felipe.ballast.recurrences.RecurrencesApi.withRule;
import static com.felipe.ballast.recurrences.RecurrencesApi.without;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Hoje fixo em 2026-10-19 (meio-dia em São Paulo), para o horizonte ser previsível.
@IntegrationTest
class RecurrenceCreationTest {

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

  @Test
  void monthlyDay5MaterializesFromNextMonthNotFromThePast() throws Exception {
    JsonNode created = api.createOk(actor.session(), monthlyBody(actor));

    String id = created.get("recorrencia").get("id").asString();
    List<LocalDate> dates = db.occurrenceDates(id);
    assertThat(dates).hasSize(12);
    assertThat(dates.getFirst()).isEqualTo(d("2026-11-05"));
    assertThat(dates.getLast()).isEqualTo(d("2027-10-05"));
    assertThat(dates).doesNotContain(d("2026-10-05"));
    assertThat(created.get("ocorrenciasMaterializadas").asInt()).isEqualTo(12);
  }

  @Test
  void theOccurrenceOnTodayAndTheOneExactlyTwelveMonthsAheadBothEnter() throws Exception {
    Map<String, Object> body =
        withRule(
            monthlyBody(actor), rule("mensal", Map.of("diaDoMes", 19, "inicio", "2026-01-01")));

    JsonNode created = api.createOk(actor.session(), body);

    List<LocalDate> dates = db.occurrenceDates(created.get("recorrencia").get("id").asString());
    assertThat(dates).hasSize(13);
    assertThat(dates.getFirst()).isEqualTo(d("2026-10-19"));
    assertThat(dates.getLast()).isEqualTo(d("2027-10-19"));
  }

  @Test
  void aDailySeriesFillsTheWholeHorizonAndNothingBeyondIt() throws Exception {
    Map<String, Object> body =
        withRule(monthlyBody(actor), rule("diaria", Map.of("inicio", "2026-10-19")));

    JsonNode created = api.createOk(actor.session(), body);

    List<LocalDate> dates = db.occurrenceDates(created.get("recorrencia").get("id").asString());
    assertThat(dates).hasSize(366);
    assertThat(dates.getLast()).isEqualTo(d("2027-10-19"));
  }

  @Test
  void aSeriesThatStartsInTheFutureMaterializesFromItsStart() throws Exception {
    Map<String, Object> body =
        withRule(monthlyBody(actor), rule("mensal", Map.of("diaDoMes", 1, "inicio", "2027-03-01")));

    JsonNode created = api.createOk(actor.session(), body);

    List<LocalDate> dates = db.occurrenceDates(created.get("recorrencia").get("id").asString());
    assertThat(dates.getFirst()).isEqualTo(d("2027-03-01"));
    assertThat(dates.getLast()).isEqualTo(d("2027-10-01"));
  }

  @Test
  void maxOccurrencesCountsTheOnesThatFellBeforeToday() throws Exception {
    // 18, 19 e 20 de outubro: a primeira já passou, mas entra na conta das 3.
    Map<String, Object> body =
        withRule(
            monthlyBody(actor),
            rule("diaria", Map.of("inicio", "2026-10-18", "ocorrenciasMax", 3)));

    JsonNode created = api.createOk(actor.session(), body);

    assertThat(db.occurrenceDates(created.get("recorrencia").get("id").asString()))
        .containsExactly(d("2026-10-19"), d("2026-10-20"));
    assertThat(created.get("ocorrenciasMaterializadas").asInt()).isEqualTo(2);
  }

  @Test
  void endDateStopsTheSeriesAndIsIncluded() throws Exception {
    Map<String, Object> body =
        withRule(
            monthlyBody(actor),
            rule("mensal", Map.of("diaDoMes", 5, "inicio", "2026-01-01", "fim", "2027-01-05")));

    JsonNode created = api.createOk(actor.session(), body);

    List<LocalDate> dates = db.occurrenceDates(created.get("recorrencia").get("id").asString());
    assertThat(dates).hasSize(3);
    assertThat(dates.getLast()).isEqualTo(d("2027-01-05"));
  }

  @Test
  void aSeriesThatEndedBeforeTodayIsCreatedWithNothingToMaterialize() throws Exception {
    Map<String, Object> body =
        withRule(
            monthlyBody(actor),
            rule("mensal", Map.of("diaDoMes", 5, "inicio", "2026-01-01", "fim", "2026-06-30")));

    JsonNode created = api.createOk(actor.session(), body);

    assertThat(created.get("ocorrenciasMaterializadas").asInt()).isZero();
    assertThat(created.get("recorrencia").get("proximaOcorrencia").isNull()).isTrue();
  }

  @Test
  void theResponseCarriesTemplateRuleAndNextOccurrence() throws Exception {
    Map<String, Object> body = with(monthlyBody(actor), "descricao", "  todo mês  ");

    MockHttpServletResponse response = api.create(actor.session(), body);

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode recurrence = RecurrencesApi.body(response).get("recorrencia");
    assertThat(recurrence.get("id").asString()).matches("[0-9a-f-]{36}");
    assertThat(recurrence.get("tipo").asString()).isEqualTo("despesa");
    assertThat(recurrence.get("titulo").asString()).isEqualTo("Aluguel");
    assertThat(recurrence.get("descricao").asString()).isEqualTo("todo mês");
    assertThat(recurrence.get("valorCentavos").asLong()).isEqualTo(150_000L);
    assertThat(recurrence.get("categoriaId").asString()).isEqualTo(actor.expenseCategoryId());
    assertThat(recurrence.get("contaId").asString()).isEqualTo(actor.accountId());
    assertThat(recurrence.get("ativo").asBoolean()).isTrue();
    assertThat(recurrence.get("criadoEm").asString()).isNotBlank();
    JsonNode rule = recurrence.get("regra");
    assertThat(rule.get("frequencia").asString()).isEqualTo("mensal");
    assertThat(rule.get("intervalo").asInt()).isEqualTo(1);
    assertThat(rule.get("diaDoMes").asInt()).isEqualTo(5);
    assertThat(rule.get("diaDaSemana").isNull()).isTrue();
    assertThat(rule.get("inicio").asString()).isEqualTo("2026-01-01");
    assertThat(rule.get("fim").isNull()).isTrue();
    assertThat(rule.get("ocorrenciasMax").isNull()).isTrue();
    assertThat(recurrence.get("proximaOcorrencia").asString()).isEqualTo("2026-11-05");
  }

  @Test
  void eachOccurrenceIsAPlannedEntryCopiedFromTheTemplate() throws Exception {
    JsonNode created = api.createOk(actor.session(), monthlyBody(actor));
    String id = created.get("recorrencia").get("id").asString();

    List<Map<String, Object>> rows =
        jdbc.sql("SELECT * FROM lancamento WHERE template_id = :id ORDER BY ocorrencia_data")
            .param("id", UUID.fromString(id))
            .query()
            .listOfRows();

    assertThat(rows).hasSize(12);
    for (Map<String, Object> row : rows) {
      assertThat(row.get("status")).isEqualTo("previsto");
      assertThat(row.get("desgarrado")).isEqualTo(false);
      assertThat(row.get("usuario_id")).isEqualTo(actor.user().id());
      assertThat(row.get("tipo")).isEqualTo("despesa");
      assertThat(row.get("titulo")).isEqualTo("Aluguel");
      assertThat(row.get("descricao")).isNull();
      assertThat(new java.math.BigDecimal(row.get("valor").toString()))
          .isEqualByComparingTo("1500.00");
      assertThat(row.get("categoria_id").toString()).isEqualTo(actor.expenseCategoryId());
      assertThat(row.get("conta_id").toString()).isEqualTo(actor.accountId());
      assertThat(row.get("data_competencia")).isEqualTo(row.get("ocorrencia_data"));
      assertThat(row.get("data_caixa")).isEqualTo(row.get("ocorrencia_data"));
    }
  }

  @Test
  void plannedEntriesAppearInTheMonthListAsPlannedAndLeaveTheBalanceAlone() throws Exception {
    long before = api.accountBalance(actor.session(), actor.accountId());
    JsonNode created = api.createOk(actor.session(), monthlyBody(actor));
    String id = created.get("recorrencia").get("id").asString();

    JsonNode list =
        RecurrencesApi.body(api.listEntries(actor.session(), "2026-11-01", "2026-11-30"));

    assertThat(list).hasSize(1);
    assertThat(list.get(0).get("status").asString()).isEqualTo("previsto");
    assertThat(list.get(0).get("templateId").asString()).isEqualTo(id);
    assertThat(list.get(0).get("dataCaixa").asString()).isEqualTo("2026-11-05");
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(before);
  }

  // Validação na borda: nada é gravado e a mensagem é fixa, sem o valor enviado.

  static Stream<Arguments> invalidBodies() {
    Map<String, Object> mensal = Map.of("diaDoMes", 5, "inicio", "2026-01-01");
    return Stream.of(
        Arguments.of("sem titulo", "titulo", null, true, RecurrenceRequestMessages.TITLE),
        Arguments.of("titulo em branco", "titulo", "   ", false, RecurrenceRequestMessages.TITLE),
        Arguments.of(
            "titulo longo", "titulo", "x".repeat(101), false, RecurrenceRequestMessages.TITLE),
        Arguments.of(
            "titulo com controle", "titulo", "a\u0007b", false, RecurrenceRequestMessages.TITLE),
        Arguments.of(
            "descricao longa",
            "descricao",
            "x".repeat(501),
            false,
            RecurrenceRequestMessages.DESCRIPTION),
        Arguments.of("valor zero", "valorCentavos", 0, false, RecurrenceRequestMessages.AMOUNT),
        Arguments.of(
            "valor acima do teto",
            "valorCentavos",
            9_007_199_254_740_992L,
            false,
            RecurrenceRequestMessages.AMOUNT),
        Arguments.of("sem valor", "valorCentavos", null, true, RecurrenceRequestMessages.AMOUNT),
        Arguments.of("sem categoria", "categoriaId", null, true, "categoriaId é obrigatório"),
        Arguments.of("sem conta", "contaId", null, true, "contaId é obrigatório"),
        Arguments.of("sem regra", "regra", null, true, "regra é obrigatória"),
        Arguments.of(
            "tipo transferencia", "tipo", "transferencia", false, RecurrenceRequestMessages.TYPE),
        Arguments.of(
            "sem frequencia",
            "regra",
            Map.of("diaDoMes", 5, "inicio", "2026-01-01"),
            false,
            "frequencia deve ser diaria, semanal, mensal ou anual"),
        Arguments.of(
            "frequencia desconhecida",
            "regra",
            rule("quinzenal", Map.of("diaDoMes", 5, "inicio", "2026-01-01")),
            false,
            "frequencia deve ser diaria, semanal, mensal ou anual"),
        Arguments.of(
            "intervalo zero",
            "regra",
            rule("mensal", Map.of("diaDoMes", 5, "inicio", "2026-01-01", "intervalo", 0)),
            false,
            "intervalo deve ser um inteiro entre 1 e 99"),
        Arguments.of(
            "intervalo 100",
            "regra",
            rule("mensal", Map.of("diaDoMes", 5, "inicio", "2026-01-01", "intervalo", 100)),
            false,
            "intervalo deve ser um inteiro entre 1 e 99"),
        Arguments.of(
            "dia do mes 0",
            "regra",
            rule("mensal", Map.of("diaDoMes", 0, "inicio", "2026-01-01")),
            false,
            "diaDoMes deve ser um inteiro entre 1 e 31"),
        Arguments.of(
            "dia do mes 32",
            "regra",
            rule("mensal", Map.of("diaDoMes", 32, "inicio", "2026-01-01")),
            false,
            "diaDoMes deve ser um inteiro entre 1 e 31"),
        Arguments.of(
            "dia da semana 7",
            "regra",
            rule("semanal", Map.of("diaDaSemana", 7, "inicio", "2026-01-01")),
            false,
            "diaDaSemana deve ser um inteiro entre 0 e 6"),
        Arguments.of(
            "dia da semana negativo",
            "regra",
            rule("semanal", Map.of("diaDaSemana", -1, "inicio", "2026-01-01")),
            false,
            "diaDaSemana deve ser um inteiro entre 0 e 6"),
        Arguments.of(
            "sem inicio",
            "regra",
            rule("mensal", Map.of("diaDoMes", 5)),
            false,
            "inicio deve estar no formato AAAA-MM-DD, com ano entre 1900 e 2199"),
        Arguments.of(
            "inicio 30 de fevereiro",
            "regra",
            rule("mensal", Map.of("diaDoMes", 5, "inicio", "2026-02-30")),
            false,
            "inicio deve estar no formato AAAA-MM-DD, com ano entre 1900 e 2199"),
        Arguments.of(
            "inicio antes de 1900",
            "regra",
            rule("mensal", Map.of("diaDoMes", 5, "inicio", "1899-12-31")),
            false,
            "inicio deve estar no formato AAAA-MM-DD, com ano entre 1900 e 2199"),
        Arguments.of(
            "fim malformado",
            "regra",
            rule("mensal", Map.of("diaDoMes", 5, "inicio", "2026-01-01", "fim", "amanha")),
            false,
            "fim deve estar no formato AAAA-MM-DD, com ano entre 1900 e 2199"),
        Arguments.of(
            "fim antes do inicio",
            "regra",
            rule("mensal", Map.of("diaDoMes", 5, "inicio", "2026-03-01", "fim", "2026-02-28")),
            false,
            "fim deve ser igual ou posterior a inicio"),
        Arguments.of(
            "fim e ocorrenciasMax juntos",
            "regra",
            rule(
                "mensal",
                Map.of(
                    "diaDoMes",
                    5,
                    "inicio",
                    "2026-01-01",
                    "fim",
                    "2026-12-01",
                    "ocorrenciasMax",
                    3)),
            false,
            "informe fim ou ocorrenciasMax, não os dois"),
        Arguments.of(
            "ocorrenciasMax zero",
            "regra",
            rule("mensal", Map.of("diaDoMes", 5, "inicio", "2026-01-01", "ocorrenciasMax", 0)),
            false,
            "ocorrenciasMax deve ser um inteiro entre 1 e 10000"),
        Arguments.of(
            "ocorrenciasMax 10001",
            "regra",
            rule("mensal", Map.of("diaDoMes", 5, "inicio", "2026-01-01", "ocorrenciasMax", 10_001)),
            false,
            "ocorrenciasMax deve ser um inteiro entre 1 e 10000"),
        Arguments.of(
            "mensal sem dia do mes",
            "regra",
            rule("mensal", Map.of("inicio", "2026-01-01")),
            false,
            "mensal exige diaDoMes"),
        Arguments.of(
            "mensal com dia da semana",
            "regra",
            rule("mensal", Map.of("diaDoMes", 5, "diaDaSemana", 1, "inicio", "2026-01-01")),
            false,
            "diaDaSemana só vale em semanal"),
        Arguments.of(
            "semanal sem dia da semana",
            "regra",
            rule("semanal", Map.of("inicio", "2026-01-01")),
            false,
            "semanal exige diaDaSemana"),
        Arguments.of(
            "semanal com dia do mes",
            "regra",
            rule("semanal", Map.of("diaDoMes", 5, "diaDaSemana", 1, "inicio", "2026-01-01")),
            false,
            "diaDoMes só vale em mensal"),
        Arguments.of(
            "diaria com dia do mes",
            "regra",
            rule("diaria", Map.of("diaDoMes", 5, "inicio", "2026-01-01")),
            false,
            "diaDoMes só vale em mensal"),
        Arguments.of(
            "anual com dia da semana",
            "regra",
            rule("anual", Map.of("diaDaSemana", 2, "inicio", "2026-01-01")),
            false,
            "diaDaSemana só vale em semanal"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidBodies")
  void invalidBodyIsRefusedWithAFixedMessageAndNothingIsWritten(
      String name, String field, Object value, boolean remove, String message) throws Exception {
    Map<String, Object> body =
        remove ? without(monthlyBody(actor), field) : with(monthlyBody(actor), field, value);

    MockHttpServletResponse response = api.create(actor.session(), body);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response)).isEqualTo("{\"erro\":\"" + message + "\"}");
    assertThat(db.countTemplates(actor.user().id())).isZero();
    assertThat(db.countRules(actor.user().id())).isZero();
    assertThat(db.countEntries(actor.user().id())).isZero();
  }

  @Test
  void undeclaredFieldsAreRefused() throws Exception {
    assertThat(api.create(actor.session(), with(monthlyBody(actor), "ativo", true)).getStatus())
        .isEqualTo(400);
    assertThat(
            api.create(
                    actor.session(),
                    withRule(
                        monthlyBody(actor),
                        rule("mensal", Map.of("diaDoMes", 5, "inicio", "2026-01-01", "x", 1))))
                .getStatus())
        .isEqualTo(400);
    assertThat(db.countTemplates(actor.user().id())).isZero();
  }

  @Test
  void aDecimalAmountIsRefused() throws Exception {
    MockHttpServletResponse response =
        api.create(actor.session(), with(monthlyBody(actor), "valorCentavos", 12.5));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(db.countTemplates(actor.user().id())).isZero();
  }

  @Test
  void anInvalidTypeValueIsRefused() throws Exception {
    assertThat(api.create(actor.session(), with(monthlyBody(actor), "tipo", "xyz")).getStatus())
        .isEqualTo(400);
    assertThat(api.create(actor.session(), with(monthlyBody(actor), "tipo", 0)).getStatus())
        .isEqualTo(400);
  }

  @Test
  void anIncomeUsesAnIncomeCategory() throws Exception {
    Map<String, Object> income =
        with(with(monthlyBody(actor), "tipo", "receita"), "categoriaId", actor.incomeCategoryId());

    assertThat(api.create(actor.session(), income).getStatus()).isEqualTo(201);
  }

  @Test
  void unknownAccountIsRefused() throws Exception {
    for (Object account : List.of(UUID.randomUUID().toString(), "não-é-uuid")) {
      MockHttpServletResponse response =
          api.create(actor.session(), with(monthlyBody(actor), "contaId", account));

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(RecurrencesApi.text(response)).isEqualTo("{\"erro\":\"conta não encontrada\"}");
    }
    assertThat(db.countTemplates(actor.user().id())).isZero();
  }

  @Test
  void anArchivedAccountIsRefused() throws Exception {
    api.setAccountArchived(actor.session(), actor.accountId(), true);

    MockHttpServletResponse response = api.create(actor.session(), monthlyBody(actor));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response)).isEqualTo("{\"erro\":\"conta arquivada\"}");
    assertThat(db.countTemplates(actor.user().id())).isZero();
    assertThat(db.countRules(actor.user().id())).isZero();
  }

  @Test
  void unknownOrWrongTypeCategoryIsRefused() throws Exception {
    for (Object category :
        List.of(UUID.randomUUID().toString(), "não-é-uuid", actor.incomeCategoryId())) {
      MockHttpServletResponse response =
          api.create(actor.session(), with(monthlyBody(actor), "categoriaId", category));

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(RecurrencesApi.text(response))
          .isEqualTo("{\"erro\":\"categoria não encontrada\"}");
    }
    assertThat(db.countTemplates(actor.user().id())).isZero();
  }

  @Test
  void withoutASessionTheRouteIs401() throws Exception {
    assertThat(api.create(null, monthlyBody(actor)).getStatus()).isEqualTo(401);
  }

  private interface RecurrenceRequestMessages {
    String TITLE = "titulo deve ter entre 1 e 100 caracteres e não pode ter caracteres de controle";
    String DESCRIPTION =
        "descricao deve ter no máximo 500 caracteres e não pode ter caracteres de controle";
    String AMOUNT = "valorCentavos deve ser um inteiro entre 1 e 9007199254740991";
    String TYPE = "tipo deve ser receita ou despesa";
  }
}
