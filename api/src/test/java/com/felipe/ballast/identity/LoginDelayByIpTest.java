package com.felipe.ballast.identity;

import static com.felipe.ballast.identity.TestUsers.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// ADR-053, decisão 4 (do dono): o atraso do login é por e-mail e IP.
@IntegrationTest
class LoginDelayByIpTest {

  private static final String WRONG = "senha-errada-12345";
  private static final String ATTACKER = "198.51.100.7";
  private static final String VICTIM = "203.0.113.9";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private TestUsers users;

  @BeforeEach
  void setUp() {
    users = new TestUsers(mvc);
    clock.freeze(Instant.parse("2026-03-10T12:00:00Z"));
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private void fail(String email, String ip, int times) throws Exception {
    for (int i = 0; i < times; i++) {
      users.login(email, WRONG, ip);
    }
  }

  @Test
  void aThirdPartyFromAnotherIpDoesNotKeepTheVictimUnderDelay() throws Exception {
    TestUsers.Registered victim = users.register();
    fail(victim.email(), ATTACKER, 10);

    assertThat(users.login(victim.email(), WRONG, ATTACKER).getStatus()).isEqualTo(429);
    assertThat(users.login(victim.email(), WRONG, VICTIM).getStatus()).isEqualTo(401);
    assertThat(users.login(victim.email(), PASSWORD, VICTIM).getStatus()).isEqualTo(200);
  }

  @Test
  void theDelayStillHoldsForTheSameIp() throws Exception {
    TestUsers.Registered user = users.register();
    fail(user.email(), ATTACKER, 4);

    MockHttpServletResponse response = users.login(user.email(), PASSWORD, ATTACKER);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("2");
  }

  // O IP vem do RemoteAddr, que o Tomcat só troca por proxy interno (ADR-044). Cabeçalho do
  // cliente não escolhe a chave: senão trocar o X-Forwarded-For zeraria o atraso.
  @Test
  void spoofedForwardingHeadersDoNotChangeTheKey() throws Exception {
    TestUsers.Registered user = users.register();
    fail(user.email(), ATTACKER, 4);

    MockHttpServletResponse response =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Forwarded-For", "192.0.2.99")
                    .header("X-Real-IP", "192.0.2.98")
                    .content("{\"email\":\"" + user.email() + "\",\"senha\":\"" + WRONG + "\"}")
                    .with(
                        request -> {
                          request.setRemoteAddr(ATTACKER);
                          return request;
                        }))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(429);
  }

  // O mesmo /64 do rate limit (ADR-049): quem troca de endereço dentro do próprio bloco não
  // ganha tentativas novas.
  @Test
  void addressesInTheSameIpv6Slash64ShareTheDelay() throws Exception {
    TestUsers.Registered user = users.register();
    for (int i = 1; i <= 4; i++) {
      users.login(user.email(), WRONG, "2001:db8:5:6::" + i);
    }

    assertThat(users.login(user.email(), WRONG, "2001:db8:5:6::ff").getStatus()).isEqualTo(429);
    assertThat(users.login(user.email(), WRONG, "2001:db8:5:7::1").getStatus()).isEqualTo(401);
  }

  @Test
  void aSuccessClearsOnlyTheRowOfItsOwnIp() throws Exception {
    TestUsers.Registered user = users.register();
    fail(user.email(), ATTACKER, 4);
    fail(user.email(), VICTIM, 2);

    assertThat(users.login(user.email(), PASSWORD, VICTIM).getStatus()).isEqualTo(200);

    List<String> ips =
        jdbc.sql("SELECT ip FROM tentativa_login WHERE email = :email")
            .param("email", user.email())
            .query(String.class)
            .list();
    assertThat(ips).containsExactly(ATTACKER);
  }

  // Sem pista de conta: e-mail inexistente se comporta como o existente, em cada IP.
  @Test
  void unknownEmailIsDelayedPerIpJustLikeAnExistingOne() throws Exception {
    TestUsers.Registered user = users.register();
    String unknown = TestUsers.uniqueEmail();

    for (String email : List.of(user.email(), unknown)) {
      fail(email, ATTACKER, 4);
      MockHttpServletResponse delayed = users.login(email, WRONG, ATTACKER);
      MockHttpServletResponse other = users.login(email, WRONG, VICTIM);

      assertThat(delayed.getStatus()).isEqualTo(429);
      assertThat(delayed.getHeader("Retry-After")).isEqualTo("2");
      assertThat(other.getStatus()).isEqualTo(401);
    }
  }
}
