package com.felipe.ballast.budgets;

import static com.felipe.ballast.budgets.BudgetsApi.body;
import static com.felipe.ballast.budgets.BudgetsApi.error;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Hoje é 15 de março de 2026 em São Paulo.
@IntegrationTest
class BudgetTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private BudgetsApi api;
  private BudgetsApi.Actor actor;
  private String groceries;
  private String leisure;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2026-03-15T15:00:00Z"));
    api = new BudgetsApi(mvc);
    actor = api.newActor();
    groceries = api.categoryId(actor.session(), "Mercado");
    leisure = api.categoryId(actor.session(), "Lazer");
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private JsonNode only(String month) throws Exception {
    JsonNode body = api.listOk(actor.session(), month);
    assertThat(body.get("orcamentos")).hasSize(1);
    return body.get("orcamentos").get(0);
  }

  private void monthlyRecurrence(String categoryId, long cents, int day) throws Exception {
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("frequencia", "mensal");
    rule.put("diaDoMes", day);
    rule.put("inicio", "2026-03-01");
    Map<String, Object> recurrence = new LinkedHashMap<>();
    recurrence.put("tipo", "despesa");
    recurrence.put("titulo", "Feira");
    recurrence.put("valorCentavos", cents);
    recurrence.put("categoriaId", categoryId);
    recurrence.put("contaId", actor.accountId());
    recurrence.put("regra", rule);
    BudgetsApi.expect(api.send(actor.session(), post("/recorrencias"), recurrence), 201);
  }

  @Test
  void splitsTheMonthIntoSpentPlannedAndLeft() throws Exception {
    api.setOk(actor.session(), groceries, 80_000, "2026-03");
    api.expense(actor, actor.accountId(), groceries, 10_000, "2026-03-02");
    // Compra no cartão conta no mês da compra, mesmo com a fatura em abril.
    api.expense(actor, api.newCard(actor), groceries, 20_000, "2026-03-10");
    // Data futura ainda não aconteceu: entra como previsto, como a recorrência sem confirmar.
    api.expense(actor, actor.accountId(), groceries, 5_000, "2026-03-20");
    monthlyRecurrence(groceries, 4_000, 25);
    // Fora do mês e de outra categoria: não contam.
    api.expense(actor, actor.accountId(), groceries, 3_000, "2026-02-28");
    api.expense(actor, actor.accountId(), groceries, 2_000, "2026-04-01");
    api.expense(actor, actor.accountId(), leisure, 7_000, "2026-03-03");

    JsonNode budget = only("2026-03");

    assertThat(budget.get("categoriaId").asString()).isEqualTo(groceries);
    assertThat(budget.get("categoriaNome").asString()).isEqualTo("Mercado");
    assertThat(budget.get("tetoCentavos").asLong()).isEqualTo(80_000);
    assertThat(budget.get("desde").asString()).isEqualTo("2026-03");
    assertThat(budget.get("gastoCentavos").asLong()).isEqualTo(30_000);
    assertThat(budget.get("previstoCentavos").asLong()).isEqualTo(9_000);
    assertThat(budget.get("restanteCentavos").asLong()).isEqualTo(50_000);
  }

  @Test
  void spendingAboveTheCeilingLeavesANegativeRest() throws Exception {
    api.setOk(actor.session(), leisure, 10_000, "2026-03");
    api.expense(actor, actor.accountId(), leisure, 12_345, "2026-03-01");

    assertThat(only("2026-03").get("restanteCentavos").asLong()).isEqualTo(-2_345);
  }

  @Test
  void theCeilingLastsUntilTheNextChangeAndRemovingEndsIt() throws Exception {
    api.setOk(actor.session(), groceries, 80_000, "2026-01");
    api.setOk(actor.session(), groceries, 100_000, "2026-04");

    assertThat(api.listOk(actor.session(), "2025-12").get("orcamentos")).isEmpty();
    assertThat(only("2026-03").get("tetoCentavos").asLong()).isEqualTo(80_000);
    assertThat(only("2026-03").get("desde").asString()).isEqualTo("2026-01");
    assertThat(only("2026-05").get("tetoCentavos").asLong()).isEqualTo(100_000);

    BudgetsApi.expect(api.remove(actor.session(), groceries, "?aPartirDe=2026-06"), 204);

    assertThat(only("2026-05").get("tetoCentavos").asLong()).isEqualTo(100_000);
    assertThat(api.listOk(actor.session(), "2026-06").get("orcamentos")).isEmpty();
    assertThat(api.listOk(actor.session(), "2027-01").get("orcamentos")).isEmpty();
  }

  @Test
  void settingTheSameMonthAgainReplacesTheCeiling() throws Exception {
    api.setOk(actor.session(), groceries, 80_000, "2026-03");
    api.setOk(actor.session(), groceries, 90_000, "2026-03");

    assertThat(only("2026-03").get("tetoCentavos").asLong()).isEqualTo(90_000);
  }

  @Test
  void aChildCategoryCountsInTheParentCeiling() throws Exception {
    UUID child = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo, pai_id)"
                + " VALUES (:id, :u, 'Hortifrúti', 'despesa', CAST(:pai AS uuid))")
        .param("id", child)
        .param("u", actor.user().id())
        .param("pai", groceries)
        .update();
    api.setOk(actor.session(), groceries, 80_000, "2026-03");
    api.expense(actor, actor.accountId(), child.toString(), 1_500, "2026-03-05");

    assertThat(only("2026-03").get("gastoCentavos").asLong()).isEqualTo(1_500);
  }

  @Test
  void listsByCategoryNameAndOnlyTheMonthsWithCeiling() throws Exception {
    api.setOk(actor.session(), leisure, 10_000, "2026-03");
    api.setOk(actor.session(), groceries, 80_000, "2026-03");

    JsonNode body = api.listOk(actor.session(), "2026-03");

    assertThat(body.get("mes").asString()).isEqualTo("2026-03");
    assertThat(body.get("orcamentos").findValuesAsString("categoriaId"))
        .containsExactly(leisure, groceries);
  }

  @Test
  void withoutMonthTheListIsTheCurrentOneInSaoPaulo() throws Exception {
    // 02:00 UTC do dia 1º de março ainda é 28 de fevereiro em São Paulo. Para trás, e não para
    // a frente, porque a sessão de 7 dias venceria.
    clock.freeze(Instant.parse("2026-03-01T02:00:00Z"));

    MockHttpServletResponse response = api.list(actor.session(), "");

    assertThat(body(BudgetsApi.expect(response, 200)).get("mes").asString()).isEqualTo("2026-02");
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-13", "26-03", "2026-3", "1899-12", "2200-01", "2026-03-01", "x"})
  void rejectsAMonthOutOfFormat(String month) throws Exception {
    MockHttpServletResponse response = api.list(actor.session(), "?mes=" + month);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(error(response)).isEqualTo("mes deve ser AAAA-MM com ano entre 1900 e 2199");
  }

  @Test
  void rejectsAnInvalidCeilingOrStartMonth() throws Exception {
    assertThat(
            error(
                BudgetsApi.expect(
                    api.set(
                        actor.session(),
                        groceries,
                        Map.of("tetoCentavos", 0, "aPartirDe", "2026-03")),
                    400)))
        .isEqualTo("tetoCentavos deve ser um inteiro entre 1 e 9007199254740991");
    assertThat(
            error(
                BudgetsApi.expect(
                    api.set(
                        actor.session(),
                        groceries,
                        Map.of("tetoCentavos", 100, "aPartirDe", "2026-03-01")),
                    400)))
        .isEqualTo("aPartirDe deve ser AAAA-MM com ano entre 1900 e 2199");
    assertThat(
            BudgetsApi.expect(
                    api.set(
                        actor.session(),
                        groceries,
                        "{\"tetoCentavos\":100,\"aPartirDe\":\"2026-03\",\"extra\":1}"),
                    400)
                .getStatus())
        .isEqualTo(400);
    assertThat(api.listOk(actor.session(), "2026-03").get("orcamentos")).isEmpty();
  }

  @Test
  void anIncomeCategoryHasNoBudget() throws Exception {
    String salary = api.categoryId(actor.session(), "Salário");

    MockHttpServletResponse response =
        api.set(actor.session(), salary, Map.of("tetoCentavos", 100, "aPartirDe", "2026-03"));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(error(response)).isEqualTo("orçamento só vale para categoria de despesa");
  }

  @Test
  void removingNeedsTheStartMonth() throws Exception {
    MockHttpServletResponse response = api.remove(actor.session(), groceries, "");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(error(response)).isEqualTo("aPartirDe deve ser AAAA-MM com ano entre 1900 e 2199");
  }
}
