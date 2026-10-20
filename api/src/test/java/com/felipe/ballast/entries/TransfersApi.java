package com.felipe.ballast.entries;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Chamadas às rotas de transferência, na mesma linha de EntriesApi.
final class TransfersApi {

  private final MockMvc mvc;

  TransfersApi(MockMvc mvc) {
    this.mvc = mvc;
  }

  static Map<String, Object> body(String fromAccountId, String toAccountId, long cents) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaOrigemId", fromAccountId);
    body.put("contaDestinoId", toAccountId);
    body.put("valorCentavos", cents);
    body.put("data", "2026-03-10");
    return body;
  }

  MockHttpServletResponse create(Cookie session, Object body) throws Exception {
    var request =
        post("/transferencias")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body instanceof String s ? s : EntriesApi.JSON.writeValueAsString(body));
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  JsonNode createOk(Cookie session, Object body) throws Exception {
    MockHttpServletResponse response = create(session, body);
    if (response.getStatus() != 201) {
      throw new IllegalStateException(
          "criação de teste falhou: "
              + response.getStatus()
              + " "
              + response.getContentAsString(StandardCharsets.UTF_8));
    }
    return EntriesApi.body(response);
  }

  MockHttpServletResponse find(Cookie session, String id) throws Exception {
    var request = get("/transferencias/" + id);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse remove(Cookie session, String id) throws Exception {
    var request = delete("/transferencias/" + id);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }
}
