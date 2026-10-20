package com.felipe.ballast.cards;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Monta o cenário pela API, como o cliente faria: usuário, conta corrente, cartão e lançamentos.
final class InvoicesApi {

  static final JsonMapper JSON = JsonMapper.builder().build();

  // Um usuário com sessão, a conta corrente que paga o cartão e o cartão.
  record Actor(
      TestUsers.Registered user,
      Cookie session,
      String checkingId,
      String cardId,
      String incomeCategoryId,
      String expenseCategoryId) {}

  private final MockMvc mvc;

  InvoicesApi(MockMvc mvc) {
    this.mvc = mvc;
  }

  Actor newActor(int closingDay, int dueDay) throws Exception {
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered user = users.register();
    Cookie session = users.session(user);
    String checking = createAccount(session, "{\"nome\":\"Corrente\",\"tipo\":\"corrente\"}");
    String card =
        createAccount(
            session,
            "{\"nome\":\"Cartão\",\"tipo\":\"cartao_credito\",\"cartao\":{\"limiteCentavos\":"
                + "1000000,\"diaFechamento\":"
                + closingDay
                + ",\"diaVencimento\":"
                + dueDay
                + ",\"contaPagamentoId\":\""
                + checking
                + "\"}}");
    return new Actor(
        user,
        session,
        checking,
        card,
        categoryId(session, "Salário"),
        categoryId(session, "Mercado"));
  }

  private String createAccount(Cookie session, String content) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/contas")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(content))
            .andReturn()
            .getResponse();
    if (response.getStatus() != 201) {
      throw new IllegalStateException("conta de teste falhou: " + response.getStatus());
    }
    return body(response).get("id").asString();
  }

  private String categoryId(Cookie session, String name) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(get("/categorias").cookie(session)).andReturn().getResponse();
    for (JsonNode item : body(response)) {
      if (item.get("nome").asString().equals(name)) {
        return item.get("id").asString();
      }
    }
    throw new IllegalStateException("categoria de teste não encontrada: " + name);
  }

  // Compra (despesa) ou estorno (receita) no cartão; a data é a da compra.
  void purchase(Actor actor, String type, long cents, String date) throws Exception {
    String category = type.equals("receita") ? actor.incomeCategoryId() : actor.expenseCategoryId();
    String content =
        "{\"tipo\":\""
            + type
            + "\",\"titulo\":\"Compra\",\"valorCentavos\":"
            + cents
            + ",\"categoriaId\":\""
            + category
            + "\",\"contaId\":\""
            + actor.cardId()
            + "\",\"data\":\""
            + date
            + "\"}";
    MockHttpServletResponse response =
        mvc.perform(
                post("/lancamentos")
                    .cookie(actor.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(content))
            .andReturn()
            .getResponse();
    if (response.getStatus() != 201) {
      throw new IllegalStateException(
          "lançamento de teste falhou: "
              + response.getStatus()
              + " "
              + response.getContentAsString(StandardCharsets.UTF_8));
    }
  }

  // Pagamento da fatura: transferência da corrente para o cartão.
  void pay(Actor actor, long cents, String date) throws Exception {
    String content =
        "{\"contaOrigemId\":\""
            + actor.checkingId()
            + "\",\"contaDestinoId\":\""
            + actor.cardId()
            + "\",\"valorCentavos\":"
            + cents
            + ",\"data\":\""
            + date
            + "\"}";
    MockHttpServletResponse response =
        mvc.perform(
                post("/transferencias")
                    .cookie(actor.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(content))
            .andReturn()
            .getResponse();
    if (response.getStatus() != 201) {
      throw new IllegalStateException("pagamento de teste falhou: " + response.getStatus());
    }
  }

  MockHttpServletResponse invoices(Cookie session, String accountId, String query)
      throws Exception {
    var request = get("/contas/" + accountId + "/faturas" + query);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  static String newUnknownId() {
    return UUID.randomUUID().toString();
  }
}
