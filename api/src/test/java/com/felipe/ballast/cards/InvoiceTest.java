package com.felipe.ballast.cards;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Cartão com fechamento no dia 15 e vencimento no dia 25: a fatura de março fecha em 15/03 e vence
// em 25/03; a anterior venceu em 25/02.
@IntegrationTest
class InvoiceTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private InvoicesApi api;
  private InvoicesApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new InvoicesApi(mvc);
    actor = api.newActor(15, 25);
    // Antes de qualquer fechamento dos testes, salvo quando o teste escolhe outro dia.
    today("2026-03-01");
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private void today(String date) {
    clock.freeze(Instant.parse(date + "T15:00:00Z"));
  }

  private JsonNode invoices(String query) throws Exception {
    MockHttpServletResponse response = api.invoices(actor.session(), actor.cardId(), query);
    assertThat(response.getStatus())
        .as(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(200);
    return InvoicesApi.body(response);
  }

  private JsonNode invoiceDueOn(String dueDate) throws Exception {
    JsonNode list = invoices("?de=" + dueDate + "&ate=" + dueDate).get("faturas");
    assertThat(list).hasSize(1);
    return list.get(0);
  }

  private void insertCardEntry(String type, String amount, String cashDate, String status) {
    String category = type.equals("receita") ? actor.incomeCategoryId() : actor.expenseCategoryId();
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,
                                    data_competencia, data_caixa, status)
            VALUES (:id, :userId, :type, 'x', :categoryId, :accountId, CAST(:amount AS numeric),
                    CAST(:date AS date), CAST(:date AS date), :status)
            """)
        .param("id", UUID.randomUUID())
        .param("userId", actor.user().id())
        .param("type", type)
        .param("categoryId", UUID.fromString(category))
        .param("accountId", UUID.fromString(actor.cardId()))
        .param("amount", amount)
        .param("date", cashDate)
        .param("status", status)
        .update();
  }

  @Test
  void purchasesLandOnTheInvoiceOfTheirClosingIncludingTheClosingDayAndTheDayAfter()
      throws Exception {
    api.purchase(actor, "despesa", 12_345, "2026-03-10");
    api.purchase(actor, "despesa", 1_001, "2026-03-15");
    api.purchase(actor, "despesa", 5_000, "2026-03-16");

    JsonNode body = invoices("?de=2026-03-01&ate=2026-04-30");

    assertThat(body.get("contaId").asString()).isEqualTo(actor.cardId());
    JsonNode list = body.get("faturas");
    assertThat(list).hasSize(2);
    assertThat(list.get(0).get("vencimento").asString()).isEqualTo("2026-03-25");
    assertThat(list.get(0).get("fechamento").asString()).isEqualTo("2026-03-15");
    assertThat(list.get(0).get("totalCentavos").asLong()).isEqualTo(13_346);
    assertThat(list.get(1).get("vencimento").asString()).isEqualTo("2026-04-27");
    assertThat(list.get(1).get("fechamento").asString()).isEqualTo("2026-04-15");
    assertThat(list.get(1).get("totalCentavos").asLong()).isEqualTo(5_000);
  }

  @Test
  void aRefundSubtractsAPlannedEntryCountsAndACancelledOneDoesNot() throws Exception {
    api.purchase(actor, "despesa", 10_000, "2026-03-10");
    api.purchase(actor, "receita", 2_050, "2026-03-12");
    insertCardEntry("despesa", "7.77", "2026-03-25", "previsto");
    insertCardEntry("despesa", "500.00", "2026-03-25", "cancelado");

    JsonNode invoice = invoiceDueOn("2026-03-25");

    assertThat(invoice.get("totalCentavos").asLong()).isEqualTo(10_000 - 2_050 + 777);
  }

  @Test
  void anIntervalWithoutPurchasesListsEveryDueDateWithZeros() throws Exception {
    JsonNode list = invoices("?de=2026-01-01&ate=2026-03-31").get("faturas");

    assertThat(list).hasSize(3);
    assertThat(list.get(0).get("vencimento").asString()).isEqualTo("2026-01-26");
    assertThat(list.get(1).get("vencimento").asString()).isEqualTo("2026-02-25");
    assertThat(list.get(2).get("vencimento").asString()).isEqualTo("2026-03-25");
    for (JsonNode invoice : list) {
      assertThat(invoice.get("totalCentavos").asLong()).isZero();
      assertThat(invoice.get("pagoCentavos").asLong()).isZero();
      assertThat(invoice.get("restanteCentavos").asLong()).isZero();
    }
  }

  @Test
  void aFullPaymentLeavesNothingToPay() throws Exception {
    api.purchase(actor, "despesa", 20_000, "2026-03-10");
    api.pay(actor, 20_000, "2026-03-25");

    JsonNode invoice = invoiceDueOn("2026-03-25");

    assertThat(invoice.get("pagoCentavos").asLong()).isEqualTo(20_000);
    assertThat(invoice.get("restanteCentavos").asLong()).isZero();
  }

  @Test
  void partialPaymentsAddUpAndLeaveTheRest() throws Exception {
    api.purchase(actor, "despesa", 20_000, "2026-03-10");
    api.pay(actor, 5_000, "2026-03-16");
    api.pay(actor, 7_550, "2026-03-20");

    JsonNode invoice = invoiceDueOn("2026-03-25");

    assertThat(invoice.get("pagoCentavos").asLong()).isEqualTo(12_550);
    assertThat(invoice.get("restanteCentavos").asLong()).isEqualTo(7_450);
  }

  @Test
  void paymentAboveTheTotalGivesANegativeRemainder() throws Exception {
    api.purchase(actor, "despesa", 20_000, "2026-03-10");
    api.pay(actor, 25_000, "2026-03-25");
    today("2026-03-26");

    JsonNode invoice = invoiceDueOn("2026-03-25");

    assertThat(invoice.get("restanteCentavos").asLong()).isEqualTo(-5_000);
    assertThat(invoice.get("situacao").asString()).isEqualTo("paga");
  }

  @Test
  void aLatePaymentBeforeTheNextClosingStillBelongsToTheSameInvoice() throws Exception {
    api.purchase(actor, "despesa", 20_000, "2026-03-10");
    api.purchase(actor, "despesa", 3_000, "2026-03-20");
    api.pay(actor, 20_000, "2026-03-27");
    api.pay(actor, 1_000, "2026-04-15");
    today("2026-04-20");

    JsonNode march = invoiceDueOn("2026-03-25");
    JsonNode april = invoiceDueOn("2026-04-27");

    assertThat(march.get("pagoCentavos").asLong()).isEqualTo(21_000);
    assertThat(march.get("restanteCentavos").asLong()).isEqualTo(-1_000);
    assertThat(march.get("situacao").asString()).isEqualTo("paga");
    assertThat(april.get("pagoCentavos").asLong()).isZero();
    assertThat(april.get("restanteCentavos").asLong()).isEqualTo(3_000);
  }

  @Test
  void aPaymentAfterTheNextClosingBelongsToTheNextInvoice() throws Exception {
    api.purchase(actor, "despesa", 3_000, "2026-03-20");
    api.pay(actor, 3_000, "2026-04-16");

    assertThat(invoiceDueOn("2026-03-25").get("pagoCentavos").asLong()).isZero();
    assertThat(invoiceDueOn("2026-04-27").get("pagoCentavos").asLong()).isEqualTo(3_000);
  }

  @Test
  void aPaymentBeforeTheClosingCountsOnTheInvoiceThatAlreadyClosed() throws Exception {
    api.purchase(actor, "despesa", 10_000, "2026-02-10");
    api.pay(actor, 10_000, "2026-03-06");
    api.pay(actor, 2_000, "2026-03-15");
    api.pay(actor, 500, "2026-03-16");

    // 15/03 é o fechamento de março: o pagamento desse dia ainda é da fatura de fevereiro.
    assertThat(invoiceDueOn("2026-02-25").get("pagoCentavos").asLong()).isEqualTo(12_000);
    assertThat(invoiceDueOn("2026-03-25").get("pagoCentavos").asLong()).isEqualTo(500);
  }

  @Test
  void aTransferOutOfTheCardIsRefusedAndTheInvoiceStaysTheSame() throws Exception {
    api.purchase(actor, "despesa", 10_000, "2026-03-10");
    String content =
        "{\"contaOrigemId\":\""
            + actor.cardId()
            + "\",\"contaDestinoId\":\""
            + actor.checkingId()
            + "\",\"valorCentavos\":4000,\"data\":\"2026-03-20\"}";
    var response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/transferencias")
                    .cookie(actor.session())
                    .contentType("application/json")
                    .content(content))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(400);

    JsonNode invoice = invoiceDueOn("2026-03-25");

    assertThat(invoice.get("totalCentavos").asLong()).isEqualTo(10_000);
    assertThat(invoice.get("pagoCentavos").asLong()).isZero();
  }

  @Test
  void theInvoiceIsOpenUntilTheClosingDayInclusive() throws Exception {
    api.purchase(actor, "despesa", 10_000, "2026-03-10");

    today("2026-03-15");
    assertThat(invoiceDueOn("2026-03-25").get("situacao").asString()).isEqualTo("aberta");
    // 15/03 às 23:30 em São Paulo já é 16/03 em UTC; vale o dia de São Paulo.
    clock.freeze(Instant.parse("2026-03-16T01:30:00Z"));
    assertThat(invoiceDueOn("2026-03-25").get("situacao").asString()).isEqualTo("aberta");
  }

  @Test
  void afterTheClosingDayItIsClosedWhileThereIsAnythingToPayAndPaidOtherwise() throws Exception {
    api.purchase(actor, "despesa", 10_000, "2026-03-10");
    today("2026-03-16");

    assertThat(invoiceDueOn("2026-03-25").get("situacao").asString()).isEqualTo("fechada");

    api.pay(actor, 4_000, "2026-03-20");
    assertThat(invoiceDueOn("2026-03-25").get("situacao").asString()).isEqualTo("fechada");

    api.pay(actor, 6_000, "2026-03-21");
    assertThat(invoiceDueOn("2026-03-25").get("situacao").asString()).isEqualTo("paga");
  }

  @Test
  void theInvoiceIsOpenByDateAloneEvenWithAnythingLeftToPay() throws Exception {
    api.purchase(actor, "despesa", 10_000, "2026-03-05");
    today("2026-03-10");

    JsonNode invoice = invoiceDueOn("2026-03-25");

    assertThat(invoice.get("restanteCentavos").asLong()).isEqualTo(10_000);
    assertThat(invoice.get("situacao").asString()).isEqualTo("aberta");
  }

  @Test
  void anEmptyInvoiceAlreadyClosedCountsAsPaid() throws Exception {
    today("2026-03-20");

    assertThat(invoiceDueOn("2026-02-25").get("situacao").asString()).isEqualTo("paga");
  }

  @Test
  void aTotalBeyondTheSafeIntegerIsRefused() throws Exception {
    insertCardEntry("despesa", "60000000000000.00", "2026-03-25", "realizado");
    insertCardEntry("despesa", "40000000000000.00", "2026-03-25", "realizado");

    MockHttpServletResponse response =
        api.invoices(actor.session(), actor.cardId(), "?de=2026-03-25&ate=2026-03-25");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"fatura fora do limite\"}");
  }

  @Test
  void aRemainderBeyondTheSafeIntegerIsRefusedEvenWhenTotalAndPaidFit() throws Exception {
    api.pay(actor, 9_007_199_254_740_900L, "2026-03-20");
    // Estorno gravado direto: pela API o saldo do cartão passaria do limite.
    insertCardEntry("receita", "90071992547409.00", "2026-03-25", "realizado");

    MockHttpServletResponse response =
        api.invoices(actor.session(), actor.cardId(), "?de=2026-03-25&ate=2026-03-25");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"fatura fora do limite\"}");
  }

  @Test
  void closingOn31stInFebruaryGivesTheLastDayOfTheMonth() throws Exception {
    InvoicesApi.Actor other = api.newActor(31, 10);
    api.purchase(other, "despesa", 9_900, "2026-02-28");
    api.purchase(other, "despesa", 100, "2026-03-01");

    MockHttpServletResponse response =
        api.invoices(other.session(), other.cardId(), "?de=2026-03-01&ate=2026-04-30");
    JsonNode list = InvoicesApi.body(response).get("faturas");

    assertThat(list).hasSize(2);
    assertThat(list.get(0).get("fechamento").asString()).isEqualTo("2026-02-28");
    assertThat(list.get(0).get("vencimento").asString()).isEqualTo("2026-03-10");
    assertThat(list.get(0).get("totalCentavos").asLong()).isEqualTo(9_900);
    assertThat(list.get(1).get("fechamento").asString()).isEqualTo("2026-03-31");
    assertThat(list.get(1).get("vencimento").asString()).isEqualTo("2026-04-10");
    assertThat(list.get(1).get("totalCentavos").asLong()).isEqualTo(100);
  }

  // Fechamento 30 e vencimento 31 em fevereiro de 2026: os dois cortam no dia 28. O pagamento do
  // dia 28 é do vencimento daquela fatura, não da anterior (ADR-032).
  @Test
  void aPaymentOnTheDueDayThatEqualsTheClosingDayCountsOnItsOwnInvoice() throws Exception {
    InvoicesApi.Actor other = api.newActor(30, 31);
    api.purchase(other, "despesa", 10_000, "2026-01-10");
    api.purchase(other, "despesa", 8_000, "2026-02-10");
    api.pay(other, 4_000, "2026-02-28");
    api.pay(other, 1_000, "2026-03-05");
    today("2026-03-06");

    MockHttpServletResponse response =
        api.invoices(other.session(), other.cardId(), "?de=2026-01-01&ate=2026-03-31");
    JsonNode list = InvoicesApi.body(response).get("faturas");

    assertThat(list).hasSize(3);
    assertThat(list.get(0).get("vencimento").asString()).isEqualTo("2026-02-02");
    assertThat(list.get(0).get("pagoCentavos").asLong()).isZero();
    assertThat(list.get(1).get("vencimento").asString()).isEqualTo("2026-03-02");
    assertThat(list.get(1).get("fechamento").asString()).isEqualTo("2026-02-28");
    assertThat(list.get(1).get("pagoCentavos").asLong()).isEqualTo(5_000);
    assertThat(list.get(1).get("restanteCentavos").asLong()).isEqualTo(3_000);
    assertThat(list.get(2).get("vencimento").asString()).isEqualTo("2026-03-31");
    assertThat(list.get(2).get("pagoCentavos").asLong()).isZero();
  }

  @Test
  void anotherUsersEntriesNeverEnterTheTotals() throws Exception {
    InvoicesApi.Actor other = api.newActor(15, 25);
    api.purchase(other, "despesa", 99_999, "2026-03-10");
    api.purchase(actor, "despesa", 1_000, "2026-03-10");

    assertThat(invoiceDueOn("2026-03-25").get("totalCentavos").asLong()).isEqualTo(1_000);
  }
}
