package com.felipe.ballast.identity;

import static com.felipe.ballast.identity.TestUsers.SESSION_COOKIE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LogoutTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private TestUsers users;

  @BeforeEach
  void setUp() {
    users = new TestUsers(mvc);
  }

  private MockHttpServletResponse logout(Cookie cookie) throws Exception {
    var request = post("/auth/logout");
    if (cookie != null) {
      request.cookie(cookie);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private Timestamp revokedAt(TestUsers.Registered user) {
    Map<String, Object> row =
        jdbc.sql("SELECT revogada_em FROM sessao WHERE usuario_id = :id")
            .param("id", user.id())
            .query()
            .singleRow();
    return (Timestamp) row.get("revogada_em");
  }

  private void assertExpiredCookie(MockHttpServletResponse response) {
    assertThat(response.getHeader("Set-Cookie"))
        .startsWith(SESSION_COOKIE + "=;")
        .contains("Max-Age=0")
        .contains("Path=/")
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Lax");
  }

  @Test
  void revokesOnTheServerAndExpiresTheCookie(CapturedOutput output) throws Exception {
    TestUsers.Registered user = users.register();
    Cookie session = users.session(user);

    MockHttpServletResponse response = logout(session);

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(response.getContentAsByteArray()).isEmpty();
    assertExpiredCookie(response);
    assertThat(revokedAt(user)).as("revogada_em: só apagar o cookie não basta").isNotNull();
    // O mesmo token, reapresentado depois, não vale mais.
    assertThat(mvc.perform(get("/auth/eu").cookie(session)).andReturn().getResponse().getStatus())
        .isEqualTo(401);
    assertThat(output.getAll())
        .contains("logout")
        .contains(user.id().toString())
        .doesNotContain(session.getValue());
  }

  @Test
  void isIdempotentAndKeepsTheFirstRevocationInstant() throws Exception {
    TestUsers.Registered user = users.register();
    Cookie session = users.session(user);
    logout(session);
    Timestamp first = revokedAt(user);

    assertThat(logout(session).getStatus()).isEqualTo(204);

    assertThat(revokedAt(user)).isEqualTo(first);
  }

  @Test
  void withoutAValidSessionItIs204TooAndExpiresTheCookie() throws Exception {
    for (Cookie cookie :
        new Cookie[] {
          null, new Cookie(SESSION_COOKIE, "nao-existe"), new Cookie(SESSION_COOKIE, "")
        }) {
      MockHttpServletResponse response = logout(cookie);

      assertThat(response.getStatus()).isEqualTo(204);
      assertExpiredCookie(response);
    }
  }

  @Test
  void doesNotReadTheBodyOrCheckTheContentType() throws Exception {
    Cookie session = users.session(users.register());

    MockHttpServletResponse response =
        mvc.perform(post("/auth/logout").cookie(session).contentType("text/plain").content("lixo"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(204);
  }

  @Test
  void aLogoutNeverRevokesAnotherUsersSession() throws Exception {
    Cookie sessionA = users.session(users.register());
    Cookie sessionB = users.session(users.register());

    logout(sessionA);

    assertThat(mvc.perform(get("/auth/eu").cookie(sessionB)).andReturn().getResponse().getStatus())
        .isEqualTo(200);
  }
}
