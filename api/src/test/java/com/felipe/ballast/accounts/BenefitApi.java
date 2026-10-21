package com.felipe.ballast.accounts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Conta de benefício e a lista de categorias como o cliente faria. Público porque os testes de
// lançamento, recorrência e projeção também precisam de uma conta de benefício.
public final class BenefitApi {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final MockMvc mvc;

  public BenefitApi(MockMvc mvc) {
    this.mvc = mvc;
  }

  // permitted nulo omite o campo do corpo.
  public MockHttpServletResponse create(Cookie session, String name, List<String> permitted)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("nome", name);
    body.put("tipo", "beneficio");
    if (permitted != null) {
      body.put("categoriasPermitidas", permitted);
    }
    return createRaw(session, JSON.writeValueAsString(body));
  }

  public MockHttpServletResponse createRaw(Cookie session, String body) throws Exception {
    var request = post("/contas").contentType(MediaType.APPLICATION_JSON).content(body);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  // Cria e devolve o id; falha o teste se não for 201.
  public String createOk(Cookie session, String name, List<String> permitted) throws Exception {
    MockHttpServletResponse response = create(session, name, permitted);
    if (response.getStatus() != 201) {
      throw new IllegalStateException(
          "criação de teste falhou: " + response.getStatus() + " " + text(response));
    }
    return body(response).get("id").asString();
  }

  public MockHttpServletResponse putList(Cookie session, String accountId, Object body)
      throws Exception {
    var request =
        put("/contas/" + accountId + "/categorias-permitidas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body instanceof String s ? s : JSON.writeValueAsString(body));
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  public MockHttpServletResponse putIds(Cookie session, String accountId, List<String> ids)
      throws Exception {
    return putList(session, accountId, Map.of("categoriaIds", ids));
  }

  public String categoryId(Cookie session, String name) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(get("/categorias").cookie(session)).andReturn().getResponse();
    for (JsonNode item : body(response)) {
      if (item.get("nome").asString().equals(name)) {
        return item.get("id").asString();
      }
    }
    throw new IllegalStateException("categoria de teste não encontrada: " + name);
  }

  public static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(text(response));
  }

  public static String text(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString(StandardCharsets.UTF_8);
  }
}
