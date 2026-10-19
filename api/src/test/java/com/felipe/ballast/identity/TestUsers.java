package com.felipe.ballast.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Cria usuário e abre sessão pela própria API, como o cliente faria.
final class TestUsers {

  static final String PASSWORD = "senha-de-teste-12345";
  static final String SESSION_COOKIE = "ballast_sessao";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  record Registered(UUID id, String email) {}

  private final MockMvc mvc;

  TestUsers(MockMvc mvc) {
    this.mvc = mvc;
  }

  static String uniqueEmail() {
    return "t" + UUID.randomUUID().toString().replace("-", "") + "@exemplo.test";
  }

  Registered register() throws Exception {
    String email = uniqueEmail();
    MockHttpServletResponse response =
        mvc.perform(
                post("/auth/cadastro")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        JSON.writeValueAsString(
                            Map.of("email", email, "senha", PASSWORD, "nome", "Fulano"))))
            .andReturn()
            .getResponse();
    if (response.getStatus() != 201) {
      throw new IllegalStateException("cadastro de teste falhou: " + response.getStatus());
    }
    JsonNode body = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    return new Registered(UUID.fromString(body.get("id").asString()), email);
  }

  MockHttpServletResponse login(String email, String password) throws Exception {
    return mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "senha", password))))
        .andReturn()
        .getResponse();
  }

  // Cadastra, faz login e devolve o cookie de sessão.
  Cookie session(Registered user) throws Exception {
    MockHttpServletResponse response = login(user.email(), PASSWORD);
    Cookie cookie = response.getCookie(SESSION_COOKIE);
    if (cookie == null) {
      throw new IllegalStateException("login de teste sem cookie: " + response.getStatus());
    }
    return cookie;
  }
}
