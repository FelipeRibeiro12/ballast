package com.felipe.ballast.reports;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Rota de relatório e o mínimo de conta e lançamento para ter o que somar, como o cliente faria.
final class ReportsApi {

  static final JsonMapper JSON = JsonMapper.builder().build();

  record Actor(TestUsers.Registered user, Cookie session, String accountId) {}

  private final MockMvc mvc;

  ReportsApi(MockMvc mvc) {
    this.mvc = mvc;
  }

  Actor newActor() throws Exception {
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered user = users.register();
    Cookie session = users.session(user);
    return new Actor(user, session, newAccount(session, "Corrente"));
  }

  String newAccount(Cookie session, String name) throws Exception {
    return body(expect(
            send(session, post("/contas"), Map.of("nome", name, "tipo", "corrente")), 201))
        .get("id")
        .asString();
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
    entry(actor, "despesa", accountId, categoryId, cents, date);
  }

  void income(Actor actor, String categoryId, long cents, String date) throws Exception {
    entry(actor, "receita", actor.accountId(), categoryId, cents, date);
  }

  void transfer(Actor actor, String toAccountId, long cents, String date) throws Exception {
    Map<String, Object> transfer = new LinkedHashMap<>();
    transfer.put("contaOrigemId", actor.accountId());
    transfer.put("contaDestinoId", toAccountId);
    transfer.put("valorCentavos", cents);
    transfer.put("data", date);
    expect(send(actor.session(), post("/transferencias"), transfer), 201);
  }

  MockHttpServletResponse report(Cookie session, String query) throws Exception {
    return send(session, get("/relatorios" + query), null);
  }

  JsonNode reportOk(Cookie session, String month) throws Exception {
    return body(expect(report(session, "?mes=" + month), 200));
  }

  private void entry(
      Actor actor, String type, String accountId, String categoryId, long cents, String date)
      throws Exception {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("tipo", type);
    entry.put("titulo", "Lançamento");
    entry.put("valorCentavos", cents);
    entry.put("categoriaId", categoryId);
    entry.put("contaId", accountId);
    entry.put("data", date);
    expect(send(actor.session(), post("/lancamentos"), entry), 201);
  }

  MockHttpServletResponse send(Cookie session, MockHttpServletRequestBuilder request, Object body)
      throws Exception {
    if (session != null) {
      request.cookie(session);
    }
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
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
}
