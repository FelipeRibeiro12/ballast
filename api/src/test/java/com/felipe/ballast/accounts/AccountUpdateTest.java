package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class AccountUpdateTest {

  @Autowired MockMvc mvc;
  private AccountsApi api;
  private Cookie session;

  @BeforeEach
  void setUp() throws Exception {
    api = new AccountsApi(mvc);
    TestUsers users = new TestUsers(mvc);
    session = users.session(users.register());
  }

  private String card(String name, String paymentId) throws Exception {
    MockHttpServletResponse created =
        api.create(
            session,
            "{\"nome\":\""
                + name
                + "\",\"tipo\":\"cartao_credito\",\"cartao\":{\"limiteCentavos\":100000,"
                + "\"diaFechamento\":10,\"diaVencimento\":20,\"contaPagamentoId\":\""
                + paymentId
                + "\"}}");
    return AccountsApi.body(created).get("id").asString();
  }

  private static String text(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  void renamesAndChangesTheInitialBalanceKeepingTheRest() throws Exception {
    String id = api.createOk(session, "Itau").get("id").asString();

    MockHttpServletResponse updated =
        api.archive(session, id, "{\"nome\":\"Itaú\",\"saldoInicialCentavos\":-1050}");

    assertThat(updated.getStatus()).isEqualTo(200);
    JsonNode body = AccountsApi.body(updated);
    assertThat(body.get("nome").asString()).isEqualTo("Itaú");
    assertThat(body.get("saldoInicialCentavos").asLong()).isEqualTo(-1050);
    assertThat(body.get("saldoCentavos").asLong()).isEqualTo(-1050);
    assertThat(body.get("arquivada").asBoolean()).isFalse();
    assertThat(AccountsApi.body(api.find(session, id))).isEqualTo(body);
  }

  @Test
  void anAbsentOrNullFieldDoesNotChange() throws Exception {
    String id = api.createOk(session, "Itaú").get("id").asString();
    api.archive(session, id, "{\"saldoInicialCentavos\":500}");

    JsonNode body =
        AccountsApi.body(api.archive(session, id, "{\"nome\":null,\"arquivada\":true}"));

    assertThat(body.get("nome").asString()).isEqualTo("Itaú");
    assertThat(body.get("saldoInicialCentavos").asLong()).isEqualTo(500);
    assertThat(body.get("arquivada").asBoolean()).isTrue();
  }

  @Test
  void anEmptyBodyIs400() throws Exception {
    String id = api.createOk(session, "Itaú").get("id").asString();

    MockHttpServletResponse refused = api.archive(session, id, "{}");

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(text(refused)).isEqualTo("{\"erro\":\"informe ao menos um campo para alterar\"}");
  }

  @Test
  void aNameOfAnotherAccountIs409AndABadNameIs400() throws Exception {
    api.createOk(session, "Nubank");
    String id = api.createOk(session, "Itaú").get("id").asString();

    assertThat(api.archive(session, id, "{\"nome\":\"NUBANK\"}").getStatus()).isEqualTo(409);
    assertThat(api.archive(session, id, "{\"nome\":\"\"}").getStatus()).isEqualTo(400);
    assertThat(api.archive(session, id, "{\"nome\":\"" + "x".repeat(101) + "\"}").getStatus())
        .isEqualTo(400);
    assertThat(api.archive(session, id, "{\"nome\":\"  itaú  \"}").getStatus())
        .as("o próprio nome em outra caixa")
        .isEqualTo(200);
  }

  @Test
  void aCryptoAccountKeepsZeroInitialBalance() throws Exception {
    String id =
        AccountsApi.body(api.create(session, "{\"nome\":\"Exchange\",\"tipo\":\"cripto\"}"))
            .get("id")
            .asString();

    MockHttpServletResponse refused = api.archive(session, id, "{\"saldoInicialCentavos\":1}");

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(text(refused)).isEqualTo("{\"erro\":\"conta cripto não tem saldo em reais\"}");
  }

  @Test
  void changesTheCardLimitAndPaymentAccountButNotItsDays() throws Exception {
    String checking = api.createOk(session, "Corrente").get("id").asString();
    String savings =
        AccountsApi.body(api.create(session, "{\"nome\":\"Poupança\",\"tipo\":\"poupanca\"}"))
            .get("id")
            .asString();
    String id = card("Cartão", checking);

    JsonNode body =
        AccountsApi.body(
            api.archive(
                session,
                id,
                "{\"cartao\":{\"limiteCentavos\":250000,\"contaPagamentoId\":\""
                    + savings
                    + "\"}}"));

    assertThat(body.get("cartao").get("limiteCentavos").asLong()).isEqualTo(250000);
    assertThat(body.get("cartao").get("contaPagamentoId").asString()).isEqualTo(savings);
    assertThat(body.get("cartao").get("diaFechamento").asInt()).isEqualTo(10);
    assertThat(api.archive(session, id, "{\"cartao\":{\"diaFechamento\":5}}").getStatus())
        .isEqualTo(400);
    assertThat(api.archive(session, id, "{\"cartao\":{\"limiteCentavos\":0}}").getStatus())
        .isEqualTo(400);
  }

  @Test
  void refusesABadPaymentAccountAndCardFieldsOutsideACard() throws Exception {
    String checking = api.createOk(session, "Corrente").get("id").asString();
    String id = card("Cartão", checking);
    String cash =
        AccountsApi.body(api.create(session, "{\"nome\":\"Carteira\",\"tipo\":\"dinheiro\"}"))
            .get("id")
            .asString();

    MockHttpServletResponse notEligible =
        api.archive(session, id, "{\"cartao\":{\"contaPagamentoId\":\"" + cash + "\"}}");
    MockHttpServletResponse notFound =
        api.archive(
            session, id, "{\"cartao\":{\"contaPagamentoId\":\"" + AccountsApi.randomId() + "\"}}");
    MockHttpServletResponse notACard =
        api.archive(session, checking, "{\"cartao\":{\"limiteCentavos\":100}}");

    assertThat(text(notEligible))
        .isEqualTo(
            "{\"erro\":\"conta de pagamento deve ser uma conta corrente ou poupança ativa\"}");
    assertThat(text(notFound)).isEqualTo("{\"erro\":\"conta de pagamento não encontrada\"}");
    assertThat(text(notACard))
        .isEqualTo("{\"erro\":\"cartao só vale para conta de cartão de crédito\"}");
    assertThat(
            AccountsApi.body(api.find(session, id))
                .get("cartao")
                .get("contaPagamentoId")
                .asString())
        .isEqualTo(checking);
  }

  @Test
  void anAccountOfAnotherUserIs404AndUnchanged() throws Exception {
    TestUsers users = new TestUsers(mvc);
    Cookie other = users.session(users.register());
    String theirs = api.createOk(other, "Deles").get("id").asString();

    MockHttpServletResponse refused = api.archive(session, theirs, "{\"nome\":\"Meu\"}");

    assertThat(refused.getStatus()).isEqualTo(404);
    assertThat(AccountsApi.body(api.find(other, theirs)).get("nome").asString()).isEqualTo("Deles");
  }
}
