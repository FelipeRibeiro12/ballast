package com.felipe.ballast.reports;

import static com.felipe.ballast.reports.ReportsApi.body;
import static com.felipe.ballast.reports.ReportsApi.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Hoje é 15 de outubro de 2026 em São Paulo. A parcela conta no mês dela e pelo valor dela
// (ADR-075), no relatório, na série mensal e no orçamento.
@IntegrationTest
class InstallmentCompetenceTest {

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  private ReportsApi api;
  private ReportsApi.Actor actor;
  private String cardId;
  private String groceries;
  private String leisure;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2026-10-15T15:00:00Z"));
    api = new ReportsApi(mvc);
    actor = api.newActor();
    cardId = api.newCard(actor);
    groceries = api.categoryId(actor.session(), "Mercado");
    leisure = api.categoryId(actor.session(), "Lazer");
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private JsonNode purchase(long totalCents, int parts, String categoryId, String date)
      throws Exception {
    Map<String, Object> purchase = new LinkedHashMap<>();
    purchase.put("titulo", "Compra");
    purchase.put("valorTotalCentavos", totalCents);
    purchase.put("parcelas", parts);
    purchase.put("categoriaId", categoryId);
    purchase.put("contaId", cardId);
    purchase.put("data", date);
    return body(expect(api.send(actor.session(), post("/compras-parceladas"), purchase), 201));
  }

  private long spentIn(String month, String categoryId) throws Exception {
    JsonNode categories = api.reportOk(actor.session(), month).get("despesasPorCategoria");
    for (JsonNode item : categories) {
      if (item.get("categoriaId").asString().equals(categoryId)) {
        return item.get("gastoCentavos").asLong();
      }
    }
    return 0;
  }

  private long totalIn(String month) throws Exception {
    return api.reportOk(actor.session(), month).get("totalDespesasCentavos").asLong();
  }

  private JsonNode budget(String month) throws Exception {
    JsonNode budgets =
        body(
            expect(
                api.send(actor.session(), get("/orcamentos").queryParam("mes", month), null), 200));
    return budgets.get("orcamentos").get(0);
  }

  @Test
  void twelveInstallmentsOf1200CountOneHundredPerMonthNeverTheWholePurchase() throws Exception {
    purchase(120_000, 12, groceries, "2026-10-05");

    assertThat(spentIn("2026-10", groceries)).isEqualTo(10_000);
    long sum = 0;
    for (int i = 0; i < 12; i++) {
      String month = YearMonth.of(2026, 10).plusMonths(i).toString();
      assertThat(spentIn(month, groceries)).as(month).isEqualTo(10_000);
      sum += spentIn(month, groceries);
    }
    assertThat(sum).isEqualTo(120_000);
    assertThat(spentIn("2027-10", groceries)).isZero();
    assertThat(spentIn("2026-09", groceries)).isZero();
  }

  @Test
  void theRestGoesToTheLastInstallmentAndTheSumClosesTheTotal() throws Exception {
    purchase(100_000, 3, groceries, "2026-10-05");

    assertThat(spentIn("2026-10", groceries)).isEqualTo(33_333);
    assertThat(spentIn("2026-11", groceries)).isEqualTo(33_333);
    assertThat(spentIn("2026-12", groceries)).isEqualTo(33_334);
    assertThat(totalIn("2026-10") + totalIn("2026-11") + totalIn("2026-12")).isEqualTo(100_000);
  }

  @Test
  void theMonthlySeriesPlacesEachInstallmentInItsOwnMonth() throws Exception {
    purchase(30_000, 3, groceries, "2026-10-05");

    JsonNode months = api.reportOk(actor.session(), "2026-12").get("receitasEDespesas");
    assertThat(months.findValues("despesaCentavos").stream().map(JsonNode::asLong).toList())
        .containsExactly(0L, 0L, 0L, 10_000L, 10_000L, 10_000L);
  }

  @Test
  void ordinaryEntriesAreUnchangedAndMixWithInstallments() throws Exception {
    api.expense(actor, actor.accountId(), groceries, 5_000, "2026-10-02");
    api.expense(actor, cardId, groceries, 7_000, "2026-10-09");
    purchase(30_000, 3, groceries, "2026-10-05");

    assertThat(spentIn("2026-10", groceries)).isEqualTo(22_000);
    assertThat(spentIn("2026-11", groceries)).isEqualTo(10_000);
  }

  @Test
  void aDeletedInstallmentLeavesItsOwnMonthOnly() throws Exception {
    JsonNode bought = purchase(30_000, 3, groceries, "2026-10-05");
    String third = bought.get("itens").get(2).get("lancamentoId").asString();

    expect(api.send(actor.session(), delete("/lancamentos/" + third), null), 204);

    assertThat(spentIn("2026-10", groceries)).isEqualTo(10_000);
    assertThat(spentIn("2026-11", groceries)).isEqualTo(10_000);
    assertThat(spentIn("2026-12", groceries)).isZero();
  }

  @Test
  void anEditedInstallmentChangesOnlyItsOwnMonth() throws Exception {
    JsonNode bought = purchase(30_000, 3, groceries, "2026-10-05");
    String second = bought.get("itens").get(1).get("lancamentoId").asString();

    Map<String, Object> edit = new LinkedHashMap<>();
    edit.put("tipo", "despesa");
    edit.put("contaId", cardId);
    edit.put("data", "2026-10-05");
    edit.put("titulo", "Compra");
    edit.put("valorCentavos", 12_345);
    edit.put("categoriaId", groceries);
    expect(api.send(actor.session(), put("/lancamentos/" + second), edit), 200);

    assertThat(spentIn("2026-10", groceries)).isEqualTo(10_000);
    assertThat(spentIn("2026-11", groceries)).isEqualTo(12_345);
    assertThat(spentIn("2026-12", groceries)).isEqualTo(10_000);
  }

  @Test
  void changingTheCategoryOfThePurchaseMovesEveryInstallment() throws Exception {
    JsonNode bought = purchase(30_000, 3, groceries, "2026-10-05");
    String purchaseId = bought.get("itens").get(0).get("lancamentoId").asString();

    expect(
        api.send(
            actor.session(),
            patch("/compras-parceladas/" + purchaseId),
            Map.of("categoriaId", leisure)),
        200);

    assertThat(spentIn("2026-10", groceries)).isZero();
    assertThat(spentIn("2026-10", leisure)).isEqualTo(10_000);
    assertThat(spentIn("2026-11", leisure)).isEqualTo(10_000);
    assertThat(spentIn("2026-12", leisure)).isEqualTo(10_000);
  }

  @Test
  void aFutureMonthShowsItsInstallmentsAndTheCurrentOneNeverTheFutureOnes() throws Exception {
    purchase(120_000, 12, groceries, "2026-10-05");

    assertThat(totalIn("2027-03")).isEqualTo(10_000);
    assertThat(totalIn("2026-10")).isEqualTo(10_000);
  }

  @Test
  void budgetAndReportAgreeOnTheCurrentMonthAndTheBudgetKeepsFutureInstallmentsAsPlanned()
      throws Exception {
    api.expense(actor, actor.accountId(), groceries, 5_000, "2026-10-02");
    purchase(120_000, 12, groceries, "2026-10-05");
    expect(
        api.send(
            actor.session(),
            put("/orcamentos/" + groceries),
            Map.of("tetoCentavos", 80_000, "aPartirDe", "2026-10")),
        204);

    JsonNode october = budget("2026-10");
    assertThat(october.get("gastoCentavos").asLong()).isEqualTo(15_000);
    assertThat(october.get("previstoCentavos").asLong()).isZero();
    assertThat(october.get("gastoCentavos").asLong()).isEqualTo(spentIn("2026-10", groceries));

    JsonNode november = budget("2026-11");
    assertThat(november.get("gastoCentavos").asLong()).isZero();
    assertThat(november.get("previstoCentavos").asLong()).isEqualTo(10_000);
  }

  @Test
  void anotherUserNeverSeesTheInstallments() throws Exception {
    purchase(120_000, 12, groceries, "2026-10-05");
    ReportsApi.Actor other = api.newActor();

    assertThat(api.reportOk(other.session(), "2026-11").get("totalDespesasCentavos").asLong())
        .isZero();
  }
}
