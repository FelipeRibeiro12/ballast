package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class CardAccountTest {

  private static final String NOT_FOUND = "conta de pagamento não encontrada";
  private static final String NOT_ELIGIBLE =
      "conta de pagamento deve ser uma conta corrente ou poupança ativa";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private AccountsApi api;
  private TestUsers users;
  private TestUsers.Registered user;
  private Cookie session;
  private String checkingId;

  @BeforeEach
  void setUp() throws Exception {
    api = new AccountsApi(mvc);
    users = new TestUsers(mvc);
    user = users.register();
    session = users.session(user);
    checkingId = api.createOk(session, "Corrente " + UUID.randomUUID()).get("id").asString();
  }

  private static String card(String name, String balance, String cardJson) {
    String balanceField = balance == null ? "" : ",\"saldoInicialCentavos\":" + balance;
    String cardField = cardJson == null ? "" : ",\"cartao\":" + cardJson;
    return "{\"nome\":\""
        + name
        + "\",\"tipo\":\"cartao_credito\""
        + balanceField
        + cardField
        + "}";
  }

  private static String cardData(
      String limit, String closing, String due, String paymentAccountId) {
    return "{\"limiteCentavos\":"
        + limit
        + ",\"diaFechamento\":"
        + closing
        + ",\"diaVencimento\":"
        + due
        + ",\"contaPagamentoId\":\""
        + paymentAccountId
        + "\"}";
  }

  private String validCard(String name) {
    return card(name, null, cardData("500000", "15", "25", checkingId));
  }

  private long countCards() {
    return jdbc.sql("SELECT count(*) FROM cartao WHERE usuario_id = :id")
        .param("id", user.id())
        .query(Long.class)
        .single();
  }

  private long countCardAccounts() {
    return jdbc.sql("SELECT count(*) FROM conta WHERE usuario_id = :id AND tipo = 'cartao_credito'")
        .param("id", user.id())
        .query(Long.class)
        .single();
  }

  private void assertRejected(MockHttpServletResponse response, String message) throws Exception {
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(AccountsApi.body(response).get("erro").asString()).isEqualTo(message);
    assertThat(countCards()).isZero();
    assertThat(countCardAccounts()).isZero();
  }

  @Test
  void createsACardAccountWithItsData() throws Exception {
    MockHttpServletResponse created =
        api.create(session, card("Nubank", "-120000", cardData("500000", "15", "25", checkingId)));

    assertThat(created.getStatus()).isEqualTo(201);
    JsonNode body = AccountsApi.body(created);
    assertThat(body.get("tipo").asString()).isEqualTo("cartao_credito");
    assertThat(body.get("saldoCentavos").asLong()).isEqualTo(-120000L);
    JsonNode cartao = body.get("cartao");
    assertThat(cartao.propertyNames())
        .containsExactlyInAnyOrder(
            "limiteCentavos",
            "diaFechamento",
            "diaVencimento",
            "contaPagamentoId",
            "disponivelCentavos");
    assertThat(cartao.get("limiteCentavos").asLong()).isEqualTo(500000L);
    assertThat(cartao.get("diaFechamento").asInt()).isEqualTo(15);
    assertThat(cartao.get("diaVencimento").asInt()).isEqualTo(25);
    assertThat(cartao.get("contaPagamentoId").asString()).isEqualTo(checkingId);
    assertThat(cartao.get("disponivelCentavos").asLong()).isEqualTo(380000L);
    assertThat(countCards()).isEqualTo(1);

    MockHttpServletResponse read = api.find(session, body.get("id").asString());
    assertThat(AccountsApi.body(read)).isEqualTo(body);
  }

  @Test
  void listShowsTheCardOnlyOnCardAccounts() throws Exception {
    api.create(session, validCard("Cartão"));

    MockHttpServletResponse list = api.list(session, "");

    assertThat(list.getStatus()).isEqualTo(200);
    for (JsonNode account : AccountsApi.body(list)) {
      if (account.get("tipo").asString().equals("cartao_credito")) {
        assertThat(account.get("cartao").get("contaPagamentoId").asString()).isEqualTo(checkingId);
      } else {
        assertThat(account.has("cartao")).isTrue();
        assertThat(account.get("cartao").isNull()).isTrue();
      }
    }
  }

  @Test
  void availableIsTheLimitMinusTheDebtAndNeverAboveTheLimit() throws Exception {
    JsonNode inCredit =
        AccountsApi.body(
            api.create(
                session, card("Crédito", "100000", cardData("500000", "5", "15", checkingId))));
    JsonNode overLimit =
        AccountsApi.body(
            api.create(
                session, card("Estourado", "-700000", cardData("500000", "5", "15", checkingId))));

    assertThat(inCredit.get("cartao").get("disponivelCentavos").asLong()).isEqualTo(500000L);
    assertThat(overLimit.get("cartao").get("disponivelCentavos").asLong()).isEqualTo(-200000L);
  }

  @Test
  void availableStaysInTheSafeIntegerRangeAtTheExtremes() throws Exception {
    String max = "9007199254740991";
    JsonNode body =
        AccountsApi.body(
            api.create(session, card("Extremo", max, cardData(max, "5", "15", checkingId))));

    assertThat(body.get("cartao").get("disponivelCentavos").asLong()).isEqualTo(9007199254740991L);
  }

  @Test
  void aCardAccountWithoutTheCardDataIsRejected() throws Exception {
    assertRejected(
        api.create(session, card("Sem dados", null, null)),
        "cartao é obrigatório para conta de cartão de crédito");
  }

  @ParameterizedTest
  @ValueSource(strings = {"corrente", "poupanca", "dinheiro"})
  void otherTypesWithCardDataAreRejected(String type) throws Exception {
    String body =
        "{\"nome\":\"X\",\"tipo\":\""
            + type
            + "\",\"cartao\":"
            + cardData("500000", "15", "25", checkingId)
            + "}";

    MockHttpServletResponse response = api.create(session, body);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(AccountsApi.body(response).get("erro").asString())
        .isEqualTo("cartao só vale para conta de cartão de crédito");
    assertThat(countCards()).isZero();
  }

  @Test
  void aPaymentAccountOfAnotherUserIsReportedAsNotFound() throws Exception {
    Cookie other = users.session(users.register());
    String otherId = api.createOk(other, "Do B").get("id").asString();

    assertRejected(
        api.create(session, card("Roubado", null, cardData("500000", "15", "25", otherId))),
        NOT_FOUND);
  }

  @Test
  void aMissingPaymentAccountIsReportedAsNotFound() throws Exception {
    assertRejected(
        api.create(
            session,
            card("Fantasma", null, cardData("500000", "15", "25", AccountsApi.randomId()))),
        NOT_FOUND);
  }

  @Test
  void aPaymentAccountThatIsNotAUuidIsReportedAsNotFound() throws Exception {
    assertRejected(
        api.create(session, card("Torto", null, cardData("500000", "15", "25", "1-1-1-1-1"))),
        NOT_FOUND);
  }

  @Test
  void anArchivedPaymentAccountIsRejected() throws Exception {
    api.setArchived(session, checkingId, true);

    assertRejected(api.create(session, validCard("Arquivada")), NOT_ELIGIBLE);
  }

  @Test
  void aCardOrCashAccountCannotPayACard() throws Exception {
    String cashId =
        AccountsApi.body(api.create(session, "{\"nome\":\"Carteira\",\"tipo\":\"dinheiro\"}"))
            .get("id")
            .asString();
    String firstCard =
        AccountsApi.body(api.create(session, validCard("Primeiro"))).get("id").asString();
    long before = countCards();

    MockHttpServletResponse byCard =
        api.create(session, card("Segundo", null, cardData("1000", "5", "10", firstCard)));
    MockHttpServletResponse byCash =
        api.create(session, card("Terceiro", null, cardData("1000", "5", "10", cashId)));

    assertThat(byCard.getStatus()).isEqualTo(400);
    assertThat(AccountsApi.body(byCard).get("erro").asString()).isEqualTo(NOT_ELIGIBLE);
    assertThat(byCash.getStatus()).isEqualTo(400);
    assertThat(AccountsApi.body(byCash).get("erro").asString()).isEqualTo(NOT_ELIGIBLE);
    assertThat(countCards()).isEqualTo(before);
    assertThat(countCardAccounts()).isEqualTo(1);
  }

  @Test
  void aSavingsAccountCanPayACard() throws Exception {
    String savingsId =
        AccountsApi.body(api.create(session, "{\"nome\":\"Poup\",\"tipo\":\"poupanca\"}"))
            .get("id")
            .asString();

    MockHttpServletResponse created =
        api.create(session, card("Pela poupança", null, cardData("1000", "5", "10", savingsId)));

    assertThat(created.getStatus()).isEqualTo(201);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"limiteCentavos\":0,\"diaFechamento\":15,\"diaVencimento\":25,\"contaPagamentoId\":\"%s\"}",
        "{\"limiteCentavos\":9007199254740992,\"diaFechamento\":15,\"diaVencimento\":25,\"contaPagamentoId\":\"%s\"}",
        "{\"diaFechamento\":15,\"diaVencimento\":25,\"contaPagamentoId\":\"%s\"}",
        "{\"limiteCentavos\":1000,\"diaFechamento\":0,\"diaVencimento\":25,\"contaPagamentoId\":\"%s\"}",
        "{\"limiteCentavos\":1000,\"diaFechamento\":32,\"diaVencimento\":25,\"contaPagamentoId\":\"%s\"}",
        "{\"limiteCentavos\":1000,\"diaVencimento\":25,\"contaPagamentoId\":\"%s\"}",
        "{\"limiteCentavos\":1000,\"diaFechamento\":15,\"diaVencimento\":0,\"contaPagamentoId\":\"%s\"}",
        "{\"limiteCentavos\":1000,\"diaFechamento\":15,\"diaVencimento\":32,\"contaPagamentoId\":\"%s\"}",
        "{\"limiteCentavos\":1000,\"diaFechamento\":15,\"contaPagamentoId\":\"%s\"}",
        "{\"limiteCentavos\":1000,\"diaFechamento\":15,\"diaVencimento\":25}",
        "{\"limiteCentavos\":1000.5,\"diaFechamento\":15,\"diaVencimento\":25,\"contaPagamentoId\":\"%s\"}",
        "{\"limiteCentavos\":1000,\"diaFechamento\":15.5,\"diaVencimento\":25,\"contaPagamentoId\":\"%s\"}",
        "{\"limiteCentavos\":1000,\"diaFechamento\":15,\"diaVencimento\":25,\"contaPagamentoId\":\"%s\",\"extra\":1}",
        "null",
        "[]"
      })
  void invalidCardDataIsRejectedWithoutSavingAnything(String cardJson) throws Exception {
    MockHttpServletResponse response =
        api.create(session, card("Invalido", null, cardJson.replace("%s", checkingId)));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(countCards()).isZero();
    assertThat(countCardAccounts()).isZero();
  }

  @Test
  void aRepeatedNameLeavesNoCardBehind() throws Exception {
    api.create(session, validCard("Repetido"));

    MockHttpServletResponse second = api.create(session, validCard("repetido"));

    assertThat(second.getStatus()).isEqualTo(409);
    assertThat(countCards()).isEqualTo(1);
    assertThat(countCardAccounts()).isEqualTo(1);
  }

  @Test
  void archivingThePaymentAccountKeepsTheCardPointingToIt() throws Exception {
    String cardId = AccountsApi.body(api.create(session, validCard("Cartão"))).get("id").asString();

    MockHttpServletResponse archived = api.setArchived(session, checkingId, true);

    assertThat(archived.getStatus()).isEqualTo(200);
    JsonNode card = AccountsApi.body(api.find(session, cardId));
    assertThat(card.get("cartao").get("contaPagamentoId").asString()).isEqualTo(checkingId);
  }

  @Test
  void aCardOfAnotherUserIsNotFound() throws Exception {
    String cardId = AccountsApi.body(api.create(session, validCard("Meu"))).get("id").asString();
    Cookie other = users.session(users.register());

    assertThat(api.find(other, cardId).getStatus()).isEqualTo(404);
    assertThat(api.list(other, "").getContentAsString()).doesNotContain(cardId);
  }
}
