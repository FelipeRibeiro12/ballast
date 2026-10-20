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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Divisão de salário (ADR-027). Hoje fixo em 2026-10-19, meio-dia em São Paulo, e o horizonte
// vai até 2027-10-19.
@IntegrationTest
class RecurrenceDivisionTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  @Autowired RecurrenceJob job;
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

  private static Map<String, Object> percentPart(int day, int centesimos) {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("diaDoMes", day);
    part.put("percentualCentesimos", centesimos);
    return part;
  }

  private static Map<String, Object> amountPart(int day, long cents) {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("diaDoMes", day);
    part.put("valorCentavos", cents);
    return part;
  }

  private static Map<String, Object> division(String mode, Object... parts) {
    Map<String, Object> division = new LinkedHashMap<>();
    division.put("modo", mode);
    division.put("parcelas", List.of(parts));
    return division;
  }

  // Salário de R$ 5.000,00, mensal desde janeiro, com a divisão dada e sem diaDoMes na regra.
  private Map<String, Object> salary(long cents, Map<String, Object> division) {
    Map<String, Object> body = with(monthlyBody(actor), "valorCentavos", cents);
    body = withRule(body, rule("mensal", Map.of("inicio", "2026-01-01")));
    return with(body, "divisao", division);
  }

  private Map<String, Object> salary(Map<String, Object> division) {
    return salary(500_000, division);
  }

  private void assertRefused(Map<String, Object> body, String message) throws Exception {
    MockHttpServletResponse response = api.create(actor.session(), body);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response)).isEqualTo("{\"erro\":\"" + message + "\"}");
    assertThat(db.countTemplates(actor.user().id())).isZero();
    assertThat(db.countRules(actor.user().id())).isZero();
    assertThat(db.countEntries(actor.user().id())).isZero();
  }

  private String templateId(JsonNode created) {
    return created.get("recorrencia").get("id").asString();
  }

  // Regra do centavo

  @Test
  void thousandInThreePartsSumsExactlyAndTheRemainderGoesToTheLast() throws Exception {
    JsonNode created =
        api.createOk(
            actor.session(),
            salary(
                100_000,
                division(
                    "percentual",
                    percentPart(10, 3333),
                    percentPart(15, 3333),
                    percentPart(20, 3334))));

    String id = templateId(created);
    JsonNode parts = created.get("recorrencia").get("divisao").get("parcelas");
    assertThat(parts.get(0).get("valorParcelaCentavos").asLong()).isEqualTo(33_330);
    assertThat(parts.get(1).get("valorParcelaCentavos").asLong()).isEqualTo(33_330);
    assertThat(parts.get(2).get("valorParcelaCentavos").asLong()).isEqualTo(33_340);
    assertThat(db.parcelRows(id).subList(0, 3))
        .containsExactly("2026-10-20#3#333.40", "2026-11-10#1#333.30", "2026-11-15#2#333.30");
    long sumOfMonth =
        jdbc.sql(
                "SELECT sum(valor) * 100 FROM lancamento WHERE template_id = :id"
                    + " AND date_trunc('month', ocorrencia_data) = DATE '2026-11-01'")
            .param("id", java.util.UUID.fromString(id))
            .query(Long.class)
            .single();
    assertThat(sumOfMonth).isEqualTo(100_000);
  }

  @Test
  void thousandInThreePartsOf3333IsRefusedBecauseTheyAddUp9999() throws Exception {
    // 33,33% três vezes é 99,99%: sobra 0,01% que ninguém recebe, então a requisição é recusada.
    assertRefused(
        salary(
            100_000,
            division(
                "percentual", percentPart(10, 3333), percentPart(15, 3333), percentPart(20, 3333))),
        PERCENT_SUM);
  }

  @Test
  void aCentInTwoHalvesIsRefusedBecauseTheFirstPartWouldBeZero() throws Exception {
    // O CHECK do lançamento exige valor maior que zero, então 0,00 + 0,01 não pode virar duas
    // ocorrências.
    assertRefused(
        salary(1, division("percentual", percentPart(10, 5000), percentPart(20, 5000))), ZERO_PART);
  }

  @Test
  void fiveThousandIn40And60() throws Exception {
    JsonNode created =
        api.createOk(
            actor.session(),
            salary(division("percentual", percentPart(15, 4000), percentPart(30, 6000))));

    JsonNode parts = created.get("recorrencia").get("divisao").get("parcelas");
    assertThat(parts.get(0).get("valorParcelaCentavos").asLong()).isEqualTo(200_000);
    assertThat(parts.get(1).get("valorParcelaCentavos").asLong()).isEqualTo(300_000);
  }

  @Test
  void hundredInTenPartsOf10() throws Exception {
    Object[] parts = new Object[10];
    for (int i = 0; i < 10; i++) {
      parts[i] = percentPart(i + 1, 1000);
    }

    JsonNode created = api.createOk(actor.session(), salary(10_000, division("percentual", parts)));

    JsonNode response = created.get("recorrencia").get("divisao").get("parcelas");
    assertThat(response).hasSize(10);
    long sum = 0;
    for (JsonNode part : response) {
      assertThat(part.get("valorParcelaCentavos").asLong()).isEqualTo(1_000);
      sum += part.get("valorParcelaCentavos").asLong();
    }
    assertThat(sum).isEqualTo(10_000);
  }

  @Test
  void amountAtTheCeilingWithPercentSumsExactly() throws Exception {
    long ceiling = 9_007_199_254_740_991L;

    JsonNode created =
        api.createOk(
            actor.session(),
            salary(
                ceiling,
                division(
                    "percentual",
                    percentPart(10, 3333),
                    percentPart(15, 3333),
                    percentPart(20, 3334))));

    long sum = 0;
    for (JsonNode part : created.get("recorrencia").get("divisao").get("parcelas")) {
      sum += part.get("valorParcelaCentavos").asLong();
    }
    assertThat(sum).isEqualTo(ceiling);
  }

  @Test
  void fixedAmountsThatAddUpToTheTotalAreAccepted() throws Exception {
    JsonNode created =
        api.createOk(
            actor.session(),
            salary(division("valor", amountPart(15, 200_000), amountPart(30, 300_000))));

    JsonNode division = created.get("recorrencia").get("divisao");
    assertThat(division.get("modo").asString()).isEqualTo("valor");
    assertThat(division.get("parcelas").get(0).get("valorCentavos").asLong()).isEqualTo(200_000);
    assertThat(division.get("parcelas").get(0).get("valorParcelaCentavos").asLong())
        .isEqualTo(200_000);
    assertThat(division.get("parcelas").get(0).has("percentualCentesimos")).isFalse();
    assertThat(division.get("parcelas").get(1).get("ordem").asInt()).isEqualTo(2);
    assertThat(created.get("recorrencia").get("regra").get("diaDoMes").asInt()).isEqualTo(15);
  }

  @Test
  void fixedAmountsThatDoNotAddUpAreRefused() throws Exception {
    assertRefused(
        salary(division("valor", amountPart(15, 200_000), amountPart(30, 299_999))), AMOUNT_SUM);
  }

  // Validação

  private static final String PERCENT_SUM = "os percentuais das parcelas devem somar 10000";
  private static final String AMOUNT_SUM = "os valores das parcelas devem somar o valorCentavos";
  private static final String ZERO_PART = "toda parcela deve valer ao menos 1 centavo";

  @Test
  void divisionInAWeeklyRuleIsRefused() throws Exception {
    Map<String, Object> body =
        withRule(
            salary(division("percentual", percentPart(15, 4000), percentPart(30, 6000))),
            rule("semanal", Map.of("diaDaSemana", 1, "inicio", "2026-01-01")));

    assertRefused(body, "divisao só vale em mensal");
  }

  @Test
  void aDayOfMonthInTheRuleTogetherWithDivisionIsRefused() throws Exception {
    Map<String, Object> body =
        withRule(
            salary(division("percentual", percentPart(15, 4000), percentPart(30, 6000))),
            rule("mensal", Map.of("diaDoMes", 5, "inicio", "2026-01-01")));

    assertRefused(body, "diaDoMes não vale junto com divisao");
  }

  @Test
  void mixedModesAreRefused() throws Exception {
    assertRefused(
        salary(division("percentual", percentPart(15, 4000), amountPart(30, 300_000))),
        "cada parcela deve trazer só o campo do modo da divisao");
  }

  @Test
  void aPartWithBothFieldsIsRefused() throws Exception {
    Map<String, Object> both = percentPart(30, 6000);
    both.put("valorCentavos", 300_000);

    assertRefused(
        salary(division("percentual", percentPart(15, 4000), both)),
        "cada parcela deve trazer só o campo do modo da divisao");
  }

  @Test
  void repeatedDaysAreRefused() throws Exception {
    assertRefused(
        salary(division("percentual", percentPart(15, 4000), percentPart(15, 6000))),
        "os dias das parcelas devem ser distintos");
  }

  @Test
  void onePartAndElevenPartsAreRefused() throws Exception {
    assertRefused(
        salary(division("percentual", percentPart(15, 9999))),
        "divisao.parcelas deve ter entre 2 e 10 parcelas");
    Object[] eleven = new Object[11];
    for (int i = 0; i < 11; i++) {
      eleven[i] = percentPart(i + 1, 909);
    }
    assertRefused(
        salary(division("percentual", eleven)), "divisao.parcelas deve ter entre 2 e 10 parcelas");
  }

  @Test
  void percentagesAddingUp9999And10001AreRefused() throws Exception {
    assertRefused(
        salary(division("percentual", percentPart(15, 4000), percentPart(30, 5999))), PERCENT_SUM);
    assertRefused(
        salary(division("percentual", percentPart(15, 4000), percentPart(30, 6001))), PERCENT_SUM);
  }

  @Test
  void outOfRangeValuesAreRefused() throws Exception {
    assertRefused(
        salary(division("percentual", percentPart(0, 4000), percentPart(30, 6000))),
        "diaDoMes da parcela deve ser um inteiro entre 1 e 31");
    assertRefused(
        salary(division("percentual", percentPart(15, 0), percentPart(30, 10_000))),
        "percentualCentesimos deve ser um inteiro entre 1 e 9999");
    assertRefused(
        salary(division("valor", amountPart(15, 0), amountPart(30, 500_000))),
        "valorCentavos da parcela deve ser um inteiro entre 1 e 9007199254740991");
  }

  @Test
  void unknownModeMissingPartsAndUnknownFieldsAreRefused() throws Exception {
    assertRefused(
        salary(division("misto", percentPart(15, 4000), percentPart(30, 6000))),
        "divisao.modo deve ser percentual ou valor");
    Map<String, Object> noParts = new LinkedHashMap<>();
    noParts.put("modo", "percentual");
    assertRefused(salary(noParts), "divisao.parcelas deve ter entre 2 e 10 parcelas");

    Map<String, Object> extraPart = percentPart(30, 6000);
    extraPart.put("extra", 1);
    MockHttpServletResponse unknownInPart =
        api.create(
            actor.session(), salary(division("percentual", percentPart(15, 4000), extraPart)));
    Map<String, Object> extraDivision =
        division("percentual", percentPart(15, 4000), percentPart(30, 6000));
    extraDivision.put("extra", 1);
    MockHttpServletResponse unknownInDivision = api.create(actor.session(), salary(extraDivision));

    assertThat(unknownInPart.getStatus()).isEqualTo(400);
    assertThat(unknownInDivision.getStatus()).isEqualTo(400);
    assertThat(db.countTemplates(actor.user().id())).isZero();
  }

  @Test
  void aMonthlyRuleWithoutDivisionStillNeedsTheDayOfMonth() throws Exception {
    Map<String, Object> body =
        withRule(monthlyBody(actor), rule("mensal", Map.of("inicio", "2026-01-01")));

    assertRefused(body, "mensal exige diaDoMes");
  }

  // Materialização

  @Test
  void days15And30GenerateTwoOccurrencesPerMonthAndSkipTheDayThatAlreadyPassed() throws Exception {
    JsonNode created =
        api.createOk(
            actor.session(),
            salary(division("percentual", percentPart(15, 4000), percentPart(30, 6000))));

    String id = templateId(created);
    List<String> rows = db.parcelRows(id);
    assertThat(rows).hasSize(24);
    assertThat(rows.getFirst()).isEqualTo("2026-10-30#2#3000.00");
    assertThat(rows).contains("2026-11-15#1#2000.00", "2026-11-30#2#3000.00");
    assertThat(rows).doesNotContain("2026-10-15#1#2000.00");
    assertThat(rows.getLast()).isEqualTo("2027-10-15#1#2000.00");
    assertThat(created.get("ocorrenciasMaterializadas").asInt()).isEqualTo(24);
  }

  @Test
  void days30And31InFebruaryGiveTwoOccurrencesOnTheLastDay() throws Exception {
    clock.freeze(Instant.parse("2027-01-10T15:00:00Z"));
    RecurrencesApi.Actor fresh = api.newActor();
    Map<String, Object> body =
        with(
            withRule(
                monthlyBody(fresh),
                rule("mensal", Map.of("inicio", "2027-02-01", "ocorrenciasMax", 1))),
            "divisao",
            division("valor", amountPart(30, 50_000), amountPart(31, 100_000)));

    String id = templateId(api.createOk(fresh.session(), body));

    assertThat(db.parcelRows(id)).containsExactly("2027-02-28#1#500.00", "2027-02-28#2#1000.00");
  }

  @Test
  void days30And31InFebruaryOfALeapYearFallOn29() throws Exception {
    clock.freeze(Instant.parse("2028-01-10T15:00:00Z"));
    RecurrencesApi.Actor fresh = api.newActor();
    Map<String, Object> body =
        with(
            withRule(
                monthlyBody(fresh),
                rule("mensal", Map.of("inicio", "2028-02-01", "ocorrenciasMax", 1))),
            "divisao",
            division("valor", amountPart(30, 50_000), amountPart(31, 100_000)));

    String id = templateId(api.createOk(fresh.session(), body));

    assertThat(db.parcelRows(id)).containsExactly("2028-02-29#1#500.00", "2028-02-29#2#1000.00");
  }

  @Test
  void maxOccurrences3GeneratesThreeMonthsOfParcels() throws Exception {
    Map<String, Object> body =
        withRule(
            salary(division("percentual", percentPart(15, 4000), percentPart(30, 6000))),
            rule("mensal", Map.of("inicio", "2026-11-01", "ocorrenciasMax", 3)));

    String id = templateId(api.createOk(actor.session(), body));

    assertThat(db.parcelRows(id))
        .containsExactly(
            "2026-11-15#1#2000.00",
            "2026-11-30#2#3000.00",
            "2026-12-15#1#2000.00",
            "2026-12-30#2#3000.00",
            "2027-01-15#1#2000.00",
            "2027-01-30#2#3000.00");
  }

  @Test
  void theEndDateFiltersByParcelDate() throws Exception {
    Map<String, Object> body =
        withRule(
            salary(division("percentual", percentPart(15, 4000), percentPart(30, 6000))),
            rule("mensal", Map.of("inicio", "2026-11-01", "fim", "2026-12-20")));

    String id = templateId(api.createOk(actor.session(), body));

    assertThat(db.occurrenceDates(id).stream().map(Object::toString).toList())
        .containsExactly("2026-11-15", "2026-11-30", "2026-12-15");
  }

  @Test
  void theJobTwiceDoesNotDuplicateAndAdvancesTheHorizonByParcel() throws Exception {
    String id =
        templateId(
            api.createOk(
                actor.session(),
                salary(division("percentual", percentPart(15, 4000), percentPart(30, 6000)))));
    job.run();
    job.run();
    assertThat(db.countOccurrences(id)).isEqualTo(24);

    clock.advance(Duration.ofDays(31));
    job.run();
    List<String> afterFirst = db.parcelRows(id);
    job.run();

    assertThat(afterFirst).hasSize(26);
    assertThat(afterFirst).contains("2027-10-30#2#3000.00", "2027-11-15#1#2000.00");
    assertThat(db.parcelRows(id)).isEqualTo(afterFirst);
  }

  @Test
  void deactivatingRemovesThePlannedParcels() throws Exception {
    String id =
        templateId(
            api.createOk(
                actor.session(),
                salary(division("percentual", percentPart(15, 4000), percentPart(30, 6000)))));

    MockHttpServletResponse response = api.deactivate(actor.session(), id);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(db.countOccurrences(id)).isZero();
  }

  @Test
  void aTemplateWithoutDivisionHasNullDivisionAndNullParcelOrder() throws Exception {
    JsonNode created = api.createOk(actor.session(), monthlyBody(actor));

    String id = templateId(created);
    assertThat(created.get("recorrencia").get("divisao").isNull()).isTrue();
    assertThat(db.parcelRows(id).getFirst()).startsWith("2026-11-05#null#");
  }

  // Ações sobre a ocorrência valem por parcela

  @Test
  void parcelOrderAppearsOnTheEntriesAndConfirmingOneParcelLeavesTheOther() throws Exception {
    String id =
        templateId(
            api.createOk(
                actor.session(),
                salary(division("percentual", percentPart(15, 4000), percentPart(30, 6000)))));
    String first = db.entryId(id, java.time.LocalDate.parse("2026-11-15"), 1);
    String second = db.entryId(id, java.time.LocalDate.parse("2026-11-30"), 2);

    MockHttpServletResponse confirmed = api.confirm(actor.session(), first);
    JsonNode listed =
        RecurrencesApi.body(api.listEntries(actor.session(), "2026-11-01", "2026-11-30"));

    assertThat(confirmed.getStatus()).isEqualTo(200);
    assertThat(RecurrencesApi.body(confirmed).get("parcelaOrdem").asInt()).isEqualTo(1);
    assertThat(listed).hasSize(2);
    for (JsonNode entry : listed) {
      boolean isFirst = entry.get("id").asString().equals(first);
      assertThat(entry.get("parcelaOrdem").asInt()).isEqualTo(isFirst ? 1 : 2);
      assertThat(entry.get("status").asString()).isEqualTo(isFirst ? "realizado" : "previsto");
    }
    assertThat(api.cancel(actor.session(), second).getStatus()).isEqualTo(200);
    assertThat(db.countByStatus(id, "cancelado")).isEqualTo(1);
    assertThat(db.parcelRows(id)).hasSize(24);
  }

  @Test
  void anEntryOfATemplateWithoutDivisionHasNullParcelOrder() throws Exception {
    api.createOk(actor.session(), monthlyBody(actor));

    JsonNode listed =
        RecurrencesApi.body(api.listEntries(actor.session(), "2026-11-01", "2026-11-30"));

    assertThat(listed).hasSize(1);
    assertThat(listed.get(0).get("parcelaOrdem").isNull()).isTrue();
  }

  // Autorização

  @Test
  void aCannotReadBsDivisionOrBsParcels() throws Exception {
    RecurrencesApi.Actor other = api.newActor();
    Map<String, Object> body = with(monthlyBody(other), "valorCentavos", 500_000);
    body = withRule(body, rule("mensal", Map.of("inicio", "2026-01-01")));
    body =
        with(body, "divisao", division("percentual", percentPart(15, 4000), percentPart(30, 6000)));
    String id = templateId(api.createOk(other.session(), body));
    String parcel = db.entryId(id, java.time.LocalDate.parse("2026-11-15"), 1);

    assertThat(api.find(actor.session(), id).getStatus()).isEqualTo(404);
    assertThat(RecurrencesApi.text(api.list(actor.session()))).isEqualTo("[]");
    assertThat(api.deactivate(actor.session(), id).getStatus()).isEqualTo(404);
    assertThat(api.confirm(actor.session(), parcel).getStatus()).isEqualTo(404);
    assertThat(api.find(other.session(), id).getStatus()).isEqualTo(200);
    assertThat(db.countOccurrences(id)).isEqualTo(24);
  }
}
