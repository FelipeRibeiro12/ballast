package com.felipe.ballast.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Cartão fecha dia 15 e vence dia 25. Em 10/03 a fatura de 25/03 ainda está aberta pela data.
@IntegrationTest
class InvoiceCloseTest {

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  private InvoicesApi api;
  private InvoicesApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new InvoicesApi(mvc);
    actor = api.newActor(15, 25);
    today("2026-03-10");
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private void today(String date) {
    clock.freeze(Instant.parse(date + "T15:00:00Z"));
  }

  private MockHttpServletResponse close(InvoicesApi.Actor who, String cardId, String due)
      throws Exception {
    return mvc.perform(
            put("/contas/" + cardId + "/faturas/" + due + "/fechamento").cookie(who.session()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse reopen(String due) throws Exception {
    return mvc.perform(
            delete("/contas/" + actor.cardId() + "/faturas/" + due + "/fechamento")
                .cookie(actor.session()))
        .andReturn()
        .getResponse();
  }

  private JsonNode invoice(String due) throws Exception {
    JsonNode list =
        InvoicesApi.body(
                api.invoices(actor.session(), actor.cardId(), "?de=" + due + "&ate=" + due))
            .get("faturas");
    assertThat(list).hasSize(1);
    return list.get(0);
  }

  @Test
  void closingByHandClosesAnOpenInvoiceAndReopeningUndoesIt() throws Exception {
    api.purchase(actor, "despesa", 100000, "2026-03-05");
    assertThat(invoice("2026-03-25").get("situacao").asString()).isEqualTo("aberta");

    assertThat(close(actor, actor.cardId(), "2026-03-25").getStatus()).isEqualTo(204);
    assertThat(close(actor, actor.cardId(), "2026-03-25").getStatus()).isEqualTo(204);
    JsonNode closed = invoice("2026-03-25");
    assertThat(closed.get("situacao").asString()).isEqualTo("fechada");
    assertThat(closed.get("fechadaManualmente").asBoolean()).isTrue();

    assertThat(reopen("2026-03-25").getStatus()).isEqualTo(204);
    assertThat(invoice("2026-03-25").get("situacao").asString()).isEqualTo("aberta");
  }

  @Test
  void aPaymentBeforeTheClosingDateCountsOnceTheInvoiceIsClosedByHand() throws Exception {
    api.purchase(actor, "despesa", 100000, "2026-03-05");
    close(actor, actor.cardId(), "2026-03-25");

    api.pay(actor, 100000, "2026-03-10");

    JsonNode paid = invoice("2026-03-25");
    assertThat(paid.get("pagoCentavos").asLong()).isEqualTo(100000);
    assertThat(paid.get("situacao").asString()).isEqualTo("paga");
    // O pagamento não sobra na fatura anterior.
    assertThat(invoice("2026-02-25").get("pagoCentavos").asLong()).isZero();
  }

  @Test
  void anInvoiceAlreadyPastItsClosingDateCannotBeClosedOrReopened() throws Exception {
    today("2026-03-16");

    assertThat(close(actor, actor.cardId(), "2026-03-25").getStatus()).isEqualTo(409);
    assertThat(reopen("2026-03-25").getStatus()).isEqualTo(409);
  }

  @Test
  void anInvoiceMonthsAheadCannotBeClosed() throws Exception {
    assertThat(close(actor, actor.cardId(), "2026-06-25").getStatus()).isEqualTo(409);
  }

  @Test
  void aDateThatIsNotAnInvoiceDueDateIsNotFound() throws Exception {
    assertThat(close(actor, actor.cardId(), "2026-03-24").getStatus()).isEqualTo(404);
    assertThat(close(actor, actor.cardId(), "abc").getStatus()).isEqualTo(404);
  }

  @Test
  void anotherUsersCardLooksLikeAnUnknownId() throws Exception {
    InvoicesApi.Actor other = api.newActor(15, 25);

    MockHttpServletResponse foreign = close(actor, other.cardId(), "2026-03-25");
    MockHttpServletResponse unknown = close(actor, InvoicesApi.newUnknownId(), "2026-03-25");

    assertThat(foreign.getStatus()).isEqualTo(404);
    assertThat(unknown.getStatus()).isEqualTo(404);
    assertThat(invoiceOf(other, "2026-03-25").get("situacao").asString()).isEqualTo("aberta");
    assertThat(
            mvc.perform(
                    delete("/contas/" + other.cardId() + "/faturas/2026-03-25/fechamento")
                        .cookie(actor.session()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }

  private JsonNode invoiceOf(InvoicesApi.Actor who, String due) throws Exception {
    return InvoicesApi.body(api.invoices(who.session(), who.cardId(), "?de=" + due + "&ate=" + due))
        .get("faturas")
        .get(0);
  }
}
