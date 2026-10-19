package com.felipe.ballast.accounts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Chamadas às rotas de contas como o cliente faria, para os testes não repetirem o MockMvc.
final class AccountsApi {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private final MockMvc mvc;

  AccountsApi(MockMvc mvc) {
    this.mvc = mvc;
  }

  MockHttpServletResponse create(Cookie session, String body) throws Exception {
    var request = post("/contas").contentType(MediaType.APPLICATION_JSON).content(body);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  // Cria e devolve o corpo; falha o teste se não for 201.
  JsonNode createOk(Cookie session, String name) throws Exception {
    MockHttpServletResponse response =
        create(session, "{\"nome\":\"" + name + "\",\"tipo\":\"corrente\"}");
    if (response.getStatus() != 201) {
      throw new IllegalStateException("criação de teste falhou: " + response.getStatus());
    }
    return body(response);
  }

  MockHttpServletResponse list(Cookie session, String query) throws Exception {
    var request = get("/contas" + query);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse find(Cookie session, String id) throws Exception {
    var request = get("/contas/" + id);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse archive(Cookie session, String id, String body) throws Exception {
    var request = patch("/contas/" + id).contentType(MediaType.APPLICATION_JSON).content(body);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse setArchived(Cookie session, String id, boolean archived)
      throws Exception {
    return archive(session, id, "{\"arquivada\":" + archived + "}");
  }

  static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  static String randomId() {
    return UUID.randomUUID().toString();
  }
}
