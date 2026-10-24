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
public final class TestUsers {

  public static final String PASSWORD = "senha-de-teste-12345";
  // Com Secure ligado (padrão dos testes) o nome leva o prefixo __Host-; a variante de
  // desenvolvimento é testada em InsecureCookieTest.
  static final String SESSION_COOKIE = "__Host-ballast_sessao";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public record Registered(UUID id, String email) {}

  private final MockMvc mvc;

  public TestUsers(MockMvc mvc) {
    this.mvc = mvc;
  }

  public static String uniqueEmail() {
    return "t" + UUID.randomUUID().toString().replace("-", "") + "@exemplo.test";
  }

  public Registered register() throws Exception {
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

  public MockHttpServletResponse login(String email, String password) throws Exception {
    return mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "senha", password))))
        .andReturn()
        .getResponse();
  }

  // Login visto de outro endereço: o RemoteAddr é o que o Tomcat entrega em produção.
  public MockHttpServletResponse login(String email, String password, String ip) throws Exception {
    return mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "senha", password)))
                .with(
                    request -> {
                      request.setRemoteAddr(ip);
                      return request;
                    }))
        .andReturn()
        .getResponse();
  }

  // Cadastra, faz login e devolve o cookie de sessão.
  public Cookie session(Registered user) throws Exception {
    MockHttpServletResponse response = login(user.email(), PASSWORD);
    Cookie cookie = response.getCookie(SESSION_COOKIE);
    if (cookie == null) {
      throw new IllegalStateException("login de teste sem cookie: " + response.getStatus());
    }
    return cookie;
  }
}
