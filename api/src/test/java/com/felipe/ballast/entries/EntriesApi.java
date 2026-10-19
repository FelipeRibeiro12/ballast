package com.felipe.ballast.entries;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Chamadas às rotas de lançamento como o cliente faria, para os testes não repetirem o MockMvc.
final class EntriesApi {

  static final JsonMapper JSON = JsonMapper.builder().build();

  // Um usuário com sessão, uma conta e uma categoria de cada tipo.
  record Actor(
      TestUsers.Registered user,
      Cookie session,
      String accountId,
      String incomeCategoryId,
      String expenseCategoryId) {}

  private final MockMvc mvc;

  EntriesApi(MockMvc mvc) {
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
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/contas/" + accountId)
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

  // Corpo válido de despesa; os testes trocam ou removem um campo por vez.
  static Map<String, Object> expenseBody(Actor actor) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tipo", "despesa");
    body.put("titulo", "Mercado do mês");
    body.put("valorCentavos", 12_345);
    body.put("categoriaId", actor.expenseCategoryId());
    body.put("contaId", actor.accountId());
    body.put("data", "2026-03-10");
    return body;
  }

  static Map<String, Object> incomeBody(Actor actor) {
    Map<String, Object> body = expenseBody(actor);
    body.put("tipo", "receita");
    body.put("titulo", "Salário de março");
    body.put("categoriaId", actor.incomeCategoryId());
    return body;
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

  MockHttpServletResponse create(Cookie session, Object body) throws Exception {
    var request = post("/lancamentos").contentType(MediaType.APPLICATION_JSON).content(json(body));
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
          "criação de teste falhou: "
              + response.getStatus()
              + " "
              + response.getContentAsString(StandardCharsets.UTF_8));
    }
    return body(response);
  }

  MockHttpServletResponse list(Cookie session, String query) throws Exception {
    var request = get("/lancamentos" + query);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse find(Cookie session, String id) throws Exception {
    var request = get("/lancamentos/" + id);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse update(Cookie session, String id, Object body) throws Exception {
    var request =
        put("/lancamentos/" + id).contentType(MediaType.APPLICATION_JSON).content(json(body));
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse remove(Cookie session, String id) throws Exception {
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

  static String randomId() {
    return UUID.randomUUID().toString();
  }
}
