package com.felipe.ballast.recurrences;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Chamadas às rotas de recorrência como o cliente faria, para os testes não repetirem o MockMvc.
final class RecurrencesApi {

  static final JsonMapper JSON = JsonMapper.builder().build();

  // Um usuário com sessão, uma conta e uma categoria de cada tipo.
  record Actor(
      TestUsers.Registered user,
      Cookie session,
      String accountId,
      String incomeCategoryId,
      String expenseCategoryId) {}

  private final MockMvc mvc;

  RecurrencesApi(MockMvc mvc) {
    this.mvc = mvc;
  }

  Actor newActor() throws Exception {
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered user = users.register();
    Cookie session = users.session(user);
    return new Actor(
        user,
        session,
        newAccount(session, "Conta " + UUID.randomUUID()),
        categoryId(session, "Salário"),
        categoryId(session, "Mercado"));
  }

  String newAccount(Cookie session, String name) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/contas")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nome\":\"" + name + "\",\"tipo\":\"corrente\"}"))
            .andReturn()
            .getResponse();
    return body(response).get("id").asString();
  }

  void setAccountArchived(Cookie session, String accountId, boolean archived) throws Exception {
    mvc.perform(
            patch("/contas/" + accountId)
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"arquivada\":" + archived + "}"))
        .andReturn();
  }

  String categoryId(Cookie session, String name) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(get("/categorias").cookie(session)).andReturn().getResponse();
    for (JsonNode item : body(response)) {
      if (item.get("nome").asString().equals(name)) {
        return item.get("id").asString();
      }
    }
    throw new IllegalStateException("categoria de teste não encontrada: " + name);
  }

  // Mensal no dia 5 desde janeiro de 2026, despesa. Os testes trocam um campo por vez.
  static Map<String, Object> monthlyBody(Actor actor) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tipo", "despesa");
    body.put("titulo", "Aluguel");
    body.put("valorCentavos", 150_000);
    body.put("categoriaId", actor.expenseCategoryId());
    body.put("contaId", actor.accountId());
    body.put("regra", rule("mensal", Map.of("diaDoMes", 5, "inicio", "2026-01-01")));
    return body;
  }

  static Map<String, Object> rule(String frequency, Map<String, Object> fields) {
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("frequencia", frequency);
    rule.putAll(fields);
    return rule;
  }

  static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
    Map<String, Object> copy = new LinkedHashMap<>(body);
    copy.put(key, value);
    return copy;
  }

  static Map<String, Object> without(Map<String, Object> body, String key) {
    Map<String, Object> copy = new LinkedHashMap<>(body);
    copy.remove(key);
    return copy;
  }

  static Map<String, Object> withRule(Map<String, Object> body, Map<String, Object> rule) {
    return with(body, "regra", rule);
  }

  MockHttpServletResponse create(Cookie session, Object body) throws Exception {
    var request = post("/recorrencias").contentType(MediaType.APPLICATION_JSON).content(json(body));
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  // Cria e devolve o corpo; falha o teste se não for 201.
  JsonNode createOk(Cookie session, Object body) throws Exception {
    MockHttpServletResponse response = create(session, body);
    if (response.getStatus() != 201) {
      throw new IllegalStateException(
          "criação de teste falhou: " + response.getStatus() + " " + text(response));
    }
    return body(response);
  }

  MockHttpServletResponse list(Cookie session) throws Exception {
    var request = get("/recorrencias");
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse find(Cookie session, String id) throws Exception {
    var request = get("/recorrencias/" + id);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse setActive(Cookie session, String id, Object body) throws Exception {
    var request =
        patch("/recorrencias/" + id).contentType(MediaType.APPLICATION_JSON).content(json(body));
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse deactivate(Cookie session, String id) throws Exception {
    return setActive(session, id, "{\"ativo\":false}");
  }

  MockHttpServletResponse listEntries(Cookie session, String from, String to) throws Exception {
    return mvc.perform(get("/lancamentos?de=" + from + "&ate=" + to).cookie(session))
        .andReturn()
        .getResponse();
  }

  MockHttpServletResponse patchEntry(Cookie session, String id, Object body) throws Exception {
    var request =
        patch("/lancamentos/" + id).contentType(MediaType.APPLICATION_JSON).content(json(body));
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse confirm(Cookie session, String id) throws Exception {
    return patchEntry(session, id, "{\"status\":\"realizado\"}");
  }

  MockHttpServletResponse cancel(Cookie session, String id) throws Exception {
    return patchEntry(session, id, "{\"status\":\"cancelado\"}");
  }

  MockHttpServletResponse putEntry(Cookie session, String id, Object body) throws Exception {
    var request =
        put("/lancamentos/" + id).contentType(MediaType.APPLICATION_JSON).content(json(body));
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse deleteEntry(Cookie session, String id) throws Exception {
    var request = delete("/lancamentos/" + id);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  long accountBalance(Cookie session, String accountId) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(get("/contas/" + accountId).cookie(session)).andReturn().getResponse();
    return body(response).get("saldoCentavos").asLong();
  }

  private static String json(Object body) throws Exception {
    return body instanceof String s ? s : JSON.writeValueAsString(body);
  }

  static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  static String text(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString(StandardCharsets.UTF_8);
  }
}
