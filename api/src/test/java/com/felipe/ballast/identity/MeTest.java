package com.felipe.ballast.identity;

import static com.felipe.ballast.identity.TestUsers.SESSION_COOKIE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.felipe.ballast.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class MeTest {

  private static final String NOT_AUTHENTICATED = "{\"erro\":\"não autenticado\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private final JsonMapper json = JsonMapper.builder().build();
  private TestUsers users;

  @BeforeEach
  void setUp() {
    users = new TestUsers(mvc);
  }

  private MockHttpServletResponse me(Cookie cookie) throws Exception {
    var request = get("/auth/eu");
    if (cookie != null) {
      request.cookie(cookie);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  @Test
  void returnsTheOwnerOfTheSession() throws Exception {
    TestUsers.Registered user = users.register();

    MockHttpServletResponse response = me(users.session(user));

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "email", "nome");
    assertThat(body.get("id").asString()).isEqualTo(user.id().toString());
    assertThat(body.get("email").asString()).isEqualTo(user.email());
    assertThat(response.getContentAsString()).doesNotContain("senha").doesNotContain("argon2");
  }

  @Test
  void aSessionNeverReturnsAnotherUser() throws Exception {
    TestUsers.Registered userA = users.register();
    TestUsers.Registered userB = users.register();
    Cookie sessionA = users.session(userA);
    users.session(userB);

    // Identificador vindo do cliente por query, cabeçalho ou cookie extra não decide dono.
    MockHttpServletResponse response =
        mvc.perform(
                get("/auth/eu")
                    .cookie(sessionA)
                    .cookie(new Cookie("usuario_id", userB.id().toString()))
                    .queryParam("id", userB.id().toString())
                    .queryParam("usuario_id", userB.id().toString())
                    .header("X-Usuario-Id", userB.id().toString()))
            .andReturn()
            .getResponse();

    JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    assertThat(body.get("id").asString()).isEqualTo(userA.id().toString());
    assertThat(response.getContentAsString()).doesNotContain(userB.email());
  }

  @Test
  void everyInvalidSessionGetsTheSame401() throws Exception {
    Cookie expired = users.session(users.register());
    jdbc.sql("UPDATE sessao SET expira_em = now() - interval '1 second' WHERE token_hash = :hash")
        .param("hash", SessionTokens.hash(expired.getValue()))
        .update();
    Cookie revoked = users.session(users.register());
    jdbc.sql("UPDATE sessao SET revogada_em = now() WHERE token_hash = :hash")
        .param("hash", SessionTokens.hash(revoked.getValue()))
        .update();

    Map<String, Cookie> cases = new LinkedHashMap<>();
    cases.put("sem cookie", null);
    cases.put("token desconhecido", new Cookie(SESSION_COOKIE, SessionTokens.generate()));
    cases.put("token lixo", new Cookie(SESSION_COOKIE, "%%%"));
    cases.put("cookie vazio", new Cookie(SESSION_COOKIE, ""));
    cases.put("sessão expirada", expired);
    cases.put("sessão revogada", revoked);

    cases.forEach(
        (name, cookie) -> {
          try {
            MockHttpServletResponse response = me(cookie);
            assertThat(response.getStatus()).as(name).isEqualTo(401);
            assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .as(name)
                .isEqualTo(NOT_AUTHENTICATED);
          } catch (Exception e) {
            throw new AssertionError(name, e);
          }
        });
  }

  @Test
  void theHashOfASessionIsNotAnAcceptedToken() throws Exception {
    Cookie session = users.session(users.register());
    String hashAsHex = java.util.HexFormat.of().formatHex(SessionTokens.hash(session.getValue()));

    assertThat(me(new Cookie(SESSION_COOKIE, hashAsHex)).getStatus()).isEqualTo(401);
  }

  @Test
  void aSessionThatExpiresLaterStillWorks() throws Exception {
    TestUsers.Registered user = users.register();
    Cookie session = users.session(user);
    jdbc.sql("UPDATE sessao SET expira_em = now() + interval '5 seconds' WHERE usuario_id = :id")
        .param("id", user.id())
        .update();

    assertThat(me(session).getStatus()).isEqualTo(200);
  }

  @Test
  void authenticatedRequestToAnUnknownRouteIs404InJson() throws Exception {
    Cookie session = users.session(users.register());

    MockHttpServletResponse response =
        mvc.perform(get("/rota-que-nao-existe").cookie(session)).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"não encontrado\"}");
  }

  // Vale o mesmo nas duas ordens: antes, um primeiro valor vazio era ignorado e um lixo derrubava
  // a sessão, e o resultado dependia de quem escreveu primeiro.
  @Test
  void aRepeatedSessionCookieIsTreatedAsNoSessionInAnyOrder() throws Exception {
    Cookie valid = users.session(users.register());

    for (Cookie[] cookies :
        new Cookie[][] {
          {new Cookie(SESSION_COOKIE, ""), valid},
          {new Cookie(SESSION_COOKIE, "lixo"), valid},
          {valid, new Cookie(SESSION_COOKIE, "lixo")},
          {valid, valid}
        }) {
      MockHttpServletResponse response =
          mvc.perform(get("/auth/eu").cookie(cookies)).andReturn().getResponse();

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(NOT_AUTHENTICATED);
    }
  }
}
