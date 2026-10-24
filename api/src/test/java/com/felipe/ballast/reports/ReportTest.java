package com.felipe.ballast.reports;

import static com.felipe.ballast.reports.ReportsApi.body;
import static com.felipe.ballast.reports.ReportsApi.error;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
class ReportTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private ReportsApi api;
  private ReportsApi.Actor actor;
  private String groceries;
  private String leisure;
  private String salary;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2026-03-15T15:00:00Z"));
    api = new ReportsApi(mvc);
    actor = api.newActor();
    groceries = api.categoryId(actor.session(), "Mercado");
    leisure = api.categoryId(actor.session(), "Lazer");
    salary = api.categoryId(actor.session(), "Salário");
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private void monthlyRecurrence(String type, String categoryId, long cents, int day)
      throws Exception {
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("frequencia", "mensal");
    rule.put("diaDoMes", day);
    rule.put("inicio", "2026-03-01");
    Map<String, Object> recurrence = new LinkedHashMap<>();
    recurrence.put("tipo", type);
    recurrence.put("titulo", "Fixa");
    recurrence.put("valorCentavos", cents);
    recurrence.put("categoriaId", categoryId);
    recurrence.put("contaId", actor.accountId());
    recurrence.put("regra", rule);
    ReportsApi.expect(api.send(actor.session(), post("/recorrencias"), recurrence), 201);
  }

  private String childOf(String parentId, String name) {
    UUID child = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo, pai_id)"
                + " VALUES (:id, :u, :nome, 'despesa', CAST(:pai AS uuid))")
        .param("id", child)
        .param("u", actor.user().id())
        .param("nome", name)
        .param("pai", parentId)
        .update();
    return child.toString();
  }

  @Test
  void sumsTheExpensesOfTheMonthByTopCategoryAndCompetence() throws Exception {
    api.expense(actor, actor.accountId(), groceries, 10_000, "2026-03-02");
    // A filha entra na categoria de cima, como no orçamento.
    api.expense(actor, actor.accountId(), childOf(groceries, "Hortifrúti"), 1_500, "2026-03-04");
    // Compra no cartão conta no mês da compra, mesmo com a fatura em abril.
    api.expense(actor, api.newCard(actor), groceries, 20_000, "2026-03-10");
    api.expense(actor, actor.accountId(), leisure, 7_000, "2026-03-03");
    // Ainda não aconteceram: data depois de hoje e recorrência sem confirmar.
    api.expense(actor, actor.accountId(), leisure, 50_000, "2026-03-20");
    monthlyRecurrence("despesa", leisure, 4_000, 25);
    // Fora do mês, receita e transferência não são despesa do mês.
    api.expense(actor, actor.accountId(), groceries, 3_000, "2026-02-28");
    api.income(actor, salary, 500_000, "2026-03-05");
    api.transfer(actor, api.newAccount(actor.session(), "Poupança"), 9_000, "2026-03-06");

    JsonNode report = api.reportOk(actor.session(), "2026-03");

    assertThat(report.get("mes").asString()).isEqualTo("2026-03");
    assertThat(report.get("totalDespesasCentavos").asLong()).isEqualTo(38_500);
    JsonNode categories = report.get("despesasPorCategoria");
    assertThat(categories).hasSize(2);
    assertThat(categories.get(0).get("categoriaId").asString()).isEqualTo(groceries);
    assertThat(categories.get(0).get("categoriaNome").asString()).isEqualTo("Mercado");
    assertThat(categories.get(0).get("gastoCentavos").asLong()).isEqualTo(31_500);
    assertThat(categories.get(1).get("categoriaId").asString()).isEqualTo(leisure);
    assertThat(categories.get(1).get("gastoCentavos").asLong()).isEqualTo(7_000);
  }

  @Test
  void aTieInSpendingIsOrderedByName() throws Exception {
    api.expense(actor, actor.accountId(), leisure, 1_000, "2026-03-02");
    api.expense(actor, actor.accountId(), groceries, 1_000, "2026-03-02");

    JsonNode categories = api.reportOk(actor.session(), "2026-03").get("despesasPorCategoria");

    assertThat(categories.findValuesAsString("categoriaNome")).containsExactly("Lazer", "Mercado");
  }

  @Test
  void comparesIncomeAndExpenseInTheSixMonthsEndingInTheMonth() throws Exception {
    api.income(actor, salary, 500_000, "2026-03-05");
    api.expense(actor, actor.accountId(), groceries, 10_000, "2026-03-02");
    api.income(actor, salary, 400_000, "2026-01-05");
    api.expense(actor, actor.accountId(), groceries, 30_000, "2025-10-31");
    // Antes da janela de seis meses e depois de hoje: ficam de fora.
    api.expense(actor, actor.accountId(), groceries, 99_000, "2025-09-30");
    api.income(actor, salary, 99_000, "2026-03-31");
    monthlyRecurrence("receita", salary, 77_000, 20);

    JsonNode months = api.reportOk(actor.session(), "2026-03").get("receitasEDespesas");

    assertThat(months.findValuesAsString("mes"))
        .containsExactly("2025-10", "2025-11", "2025-12", "2026-01", "2026-02", "2026-03");
    assertThat(months.findValues("receitaCentavos").stream().map(JsonNode::asLong).toList())
        .isEqualTo(List.of(0L, 0L, 0L, 400_000L, 0L, 500_000L));
    assertThat(months.findValues("despesaCentavos").stream().map(JsonNode::asLong).toList())
        .isEqualTo(List.of(30_000L, 0L, 0L, 0L, 0L, 10_000L));
  }

  @Test
  void aMonthWithoutEntriesHasNoCategoriesAndZeroedMonths() throws Exception {
    JsonNode report = api.reportOk(actor.session(), "2026-03");

    assertThat(report.get("despesasPorCategoria")).isEmpty();
    assertThat(report.get("totalDespesasCentavos").asLong()).isZero();
    assertThat(report.get("receitasEDespesas")).hasSize(6);
  }

  @Test
  void withoutMonthTheReportIsTheCurrentOneInSaoPaulo() throws Exception {
    // 02:00 UTC do dia 1º de março ainda é 28 de fevereiro em São Paulo.
    clock.freeze(Instant.parse("2026-03-01T02:00:00Z"));

    MockHttpServletResponse response = api.report(actor.session(), "");

    assertThat(body(ReportsApi.expect(response, 200)).get("mes").asString()).isEqualTo("2026-02");
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-13", "26-03", "2026-3", "1899-12", "2200-01", "2026-03-01", "x"})
  void rejectsAMonthOutOfFormat(String month) throws Exception {
    MockHttpServletResponse response = api.report(actor.session(), "?mes=" + month);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(error(response)).isEqualTo("mes deve ser AAAA-MM com ano entre 1900 e 2199");
  }

  @Test
  void aSumOutOfTheJsonRangeIsRefused() throws Exception {
    api.expense(actor, actor.accountId(), groceries, 9_007_199_254_740_991L, "2026-03-02");
    // Outra conta, porque o saldo de cada uma também tem teto.
    api.expense(
        actor,
        api.newAccount(actor.session(), "Reserva"),
        leisure,
        9_007_199_254_740_991L,
        "2026-03-02");

    MockHttpServletResponse response = api.report(actor.session(), "?mes=2026-03");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(error(response)).isEqualTo("relatório fora do limite");
  }
}
