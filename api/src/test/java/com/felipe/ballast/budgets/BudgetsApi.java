package com.felipe.ballast.budgets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Rotas de orçamento e o mínimo de lançamento para dar consumo, como o cliente faria.
final class BudgetsApi {

  static final JsonMapper JSON = JsonMapper.builder().build();

  record Actor(TestUsers.Registered user, Cookie session, String accountId) {}

  private final MockMvc mvc;

  BudgetsApi(MockMvc mvc) {
    this.mvc = mvc;
  }

  Actor newActor() throws Exception {
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered user = users.register();
    Cookie session = users.session(user);
    String account =
        body(send(session, post("/contas"), Map.of("nome", "Corrente", "tipo", "corrente")))
            .get("id")
            .asString();
    return new Actor(user, session, account);
  }

  String categoryId(Cookie session, String name) throws Exception {
    for (JsonNode item : body(send(session, get("/categorias"), null))) {
      if (item.get("nome").asString().equals(name)) {
        return item.get("id").asString();
      }
    }
    throw new IllegalStateException("categoria de teste não encontrada: " + name);
  }

  // Cartão com fechamento no dia 1 e vencimento no dia 10, pago pela corrente do ator.
  String newCard(Actor actor) throws Exception {
    Map<String, Object> card = new LinkedHashMap<>();
    card.put("limiteCentavos", 1_000_000);
    card.put("diaFechamento", 1);
    card.put("diaVencimento", 10);
    card.put("contaPagamentoId", actor.accountId());
    Map<String, Object> account = new LinkedHashMap<>();
    account.put("nome", "Cartão");
    account.put("tipo", "cartao_credito");
    account.put("cartao", card);
    return body(expect(send(actor.session(), post("/contas"), account), 201)).get("id").asString();
  }

  void expense(Actor actor, String accountId, String categoryId, long cents, String date)
      throws Exception {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("tipo", "despesa");
    entry.put("titulo", "Despesa");
    entry.put("valorCentavos", cents);
    entry.put("categoriaId", categoryId);
    entry.put("contaId", accountId);
    entry.put("data", date);
    expect(send(actor.session(), post("/lancamentos"), entry), 201);
  }

  MockHttpServletResponse list(Cookie session, String query) throws Exception {
    return send(session, get("/orcamentos" + query), null);
  }

  JsonNode listOk(Cookie session, String month) throws Exception {
    return body(expect(list(session, "?mes=" + month), 200));
  }

  MockHttpServletResponse set(Cookie session, String categoryId, Object body) throws Exception {
    return send(session, put("/orcamentos/" + categoryId), body);
  }

  void setOk(Cookie session, String categoryId, long cents, String fromMonth) throws Exception {
    expect(set(session, categoryId, Map.of("tetoCentavos", cents, "aPartirDe", fromMonth)), 204);
  }

  MockHttpServletResponse remove(Cookie session, String categoryId, String query) throws Exception {
    return send(session, delete("/orcamentos/" + categoryId + query), null);
  }

  // body nulo manda sem corpo; String vai como está, o resto vira JSON.
  MockHttpServletResponse send(Cookie session, MockHttpServletRequestBuilder request, Object body)
      throws Exception {
    if (session != null) {
      request.cookie(session);
    }
    if (body != null) {
      request
          .contentType(MediaType.APPLICATION_JSON)
          .content(body instanceof String text ? text : JSON.writeValueAsString(body));
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  static MockHttpServletResponse expect(MockHttpServletResponse response, int status)
      throws Exception {
    if (response.getStatus() != status) {
      throw new AssertionError(
          "esperava "
              + status
              + " e veio "
              + response.getStatus()
              + ": "
              + response.getContentAsString(StandardCharsets.UTF_8));
    }
    return response;
  }

  static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  static String error(MockHttpServletResponse response) throws Exception {
    return body(response).get("erro").asString();
  }

  static String randomId() {
    return UUID.randomUUID().toString();
  }
}
