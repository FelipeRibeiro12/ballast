package com.felipe.ballast.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.TestClock;
import com.felipe.ballast.TestcontainersConfiguration;
import com.felipe.ballast.identity.TestUsers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// Contexto próprio com limites baixos: o application.properties dos testes deixa os limites
// altos para o resto da suíte, que faz tudo do mesmo 127.0.0.1.
@Import({TestcontainersConfiguration.class, TestClock.Config.class})
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.recurrence.job-enabled=false",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1",
      "ballast.rate-limit.general-per-minute=6",
      "ballast.rate-limit.login-per-minute=2",
      "ballast.rate-limit.registration-per-hour=1",
      "ballast.rate-limit.auth-per-minute=3",
      "ballast.rate-limit.max-clients=50"
    })
@AutoConfigureMockMvc
class RateLimitTest {

  private static final String TOO_MANY = "{\"erro\":\"muitas requisições, tente mais tarde\"}";

  // Começo exato de uma hora: o Retry-After sai em conta redonda nas janelas de minuto e de hora.
  private static final Instant START = Instant.parse("2026-10-23T12:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  @Autowired JdbcClient jdbc;
  @Autowired RateLimitFilter filter;

  @BeforeEach
  void freezeClock() {
    clock.freeze(START);
    filter.clear();
  }

  @AfterEach
  void resetClock() {
    clock.reset();
  }

  private static MockHttpServletRequestBuilder from(
      MockHttpServletRequestBuilder builder, String ip) {
    return builder.with(
        request -> {
          request.setRemoteAddr(ip);
          return request;
        });
  }

  private MockHttpServletResponse call(MockHttpServletRequestBuilder builder, String ip)
      throws Exception {
    return mvc.perform(from(builder, ip)).andReturn().getResponse();
  }

  private MockHttpServletResponse login(String ip, String email) throws Exception {
    return call(
        post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"senha\":\"senha-errada-123\"}"),
        ip);
  }

  private MockHttpServletResponse register(String ip, String email) throws Exception {
    return call(
        post("/auth/cadastro")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"senha\":\"" + TestUsers.PASSWORD + "\"}"),
        ip);
  }

  private int failuresOf(String email) {
    return jdbc.sql("SELECT coalesce(max(falhas), 0) FROM tentativa_login WHERE email = :email")
        .param("email", email)
        .query(Integer.class)
        .single();
  }

  @Test
  void refusesTheRequestAboveTheGeneralLimitWith429() throws Exception {
    for (int i = 0; i < 6; i++) {
      assertThat(call(get("/auth/eu"), "203.0.113.1").getStatus()).isEqualTo(401);
    }

    MockHttpServletResponse refused = call(get("/auth/eu"), "203.0.113.1");

    assertThat(refused.getStatus()).isEqualTo(429);
    assertThat(refused.getHeader("Retry-After")).isEqualTo("60");
    assertThat(refused.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(refused.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(TOO_MANY);
  }

  @Test
  void otherAddressKeepsItsOwnCount() throws Exception {
    for (int i = 0; i < 7; i++) {
      call(get("/auth/eu"), "203.0.113.2");
    }

    assertThat(call(get("/auth/eu"), "203.0.113.3").getStatus()).isEqualTo(401);
  }

  @Test
  void nextWindowAllowsAgain() throws Exception {
    for (int i = 0; i < 7; i++) {
      call(get("/auth/eu"), "203.0.113.4");
    }
    clock.advance(Duration.ofMinutes(1));

    assertThat(call(get("/auth/eu"), "203.0.113.4").getStatus()).isEqualTo(401);
  }

  @Test
  void healthzCountsToo() throws Exception {
    for (int i = 0; i < 6; i++) {
      call(get("/healthz"), "203.0.113.5");
    }

    assertThat(call(get("/healthz"), "203.0.113.5").getStatus()).isEqualTo(429);
  }

  // O argon2 e a tabela de tentativas nem chegam a ser tocados: é isso que segura o flood de login.
  @Test
  void loginAboveTheLimitIsRefusedBeforeCountingTheAttempt() throws Exception {
    String email = TestUsers.uniqueEmail();
    assertThat(login("198.51.100.1", email).getStatus()).isEqualTo(401);
    assertThat(login("198.51.100.1", email).getStatus()).isEqualTo(401);

    MockHttpServletResponse refused = login("198.51.100.1", email);

    assertThat(refused.getStatus()).isEqualTo(429);
    assertThat(refused.getHeader("Retry-After")).isEqualTo("60");
    assertThat(failuresOf(email)).isEqualTo(2);
  }

  @Test
  void registrationAboveTheLimitIsRefusedWithoutCreatingTheUser() throws Exception {
    assertThat(register("198.51.100.2", TestUsers.uniqueEmail()).getStatus()).isEqualTo(201);
    String second = TestUsers.uniqueEmail();

    MockHttpServletResponse refused = register("198.51.100.2", second);

    assertThat(refused.getStatus()).isEqualTo(429);
    assertThat(refused.getHeader("Retry-After")).isEqualTo("3600");
    assertThat(
            jdbc.sql("SELECT count(*) FROM usuario WHERE email = :email")
                .param("email", second)
                .query(Long.class)
                .single())
        .isZero();
  }

  // Um flood espalhado por muitos IPs passa pelo limite de cada um; o teto somado de login e
  // cadastro é o que segura a CPU e a memória do argon2.
  @Test
  void loginsFromManyAddressesShareOneCeiling() throws Exception {
    for (int i = 1; i <= 3; i++) {
      assertThat(login("192.0.2." + i, TestUsers.uniqueEmail()).getStatus()).isEqualTo(401);
    }

    assertThat(login("192.0.2.4", TestUsers.uniqueEmail()).getStatus()).isEqualTo(429);
  }

  // Senão um só IP já barrado gastaria o teto somado e trancaria o login de todo mundo.
  @Test
  void refusedByItsOwnLimitDoesNotSpendTheSharedCeiling() throws Exception {
    for (int i = 0; i < 5; i++) {
      login("192.0.2.10", TestUsers.uniqueEmail());
    }

    assertThat(login("192.0.2.11", TestUsers.uniqueEmail()).getStatus()).isEqualTo(401);
  }

  @Test
  void addressesInTheSameIpv6Slash64ShareTheCount() throws Exception {
    for (int i = 1; i <= 6; i++) {
      call(get("/auth/eu"), "2001:db8:5:6::" + i);
    }

    assertThat(call(get("/auth/eu"), "2001:db8:5:6::ff").getStatus()).isEqualTo(429);
    assertThat(call(get("/auth/eu"), "2001:db8:5:7::1").getStatus()).isEqualTo(401);
  }

  // O código do MFA tem só 6 dígitos: conta no limite de login do IP e no teto somado.
  @Test
  void mfaCodeCountsAsLogin() throws Exception {
    for (int i = 0; i < 2; i++) {
      call(
          post("/auth/login/mfa")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"codigo\":\"123456\"}"),
          "198.51.100.30");
    }

    MockHttpServletResponse refused =
        call(
            post("/auth/login/mfa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"codigo\":\"123456\"}"),
            "198.51.100.30");

    assertThat(refused.getStatus()).isEqualTo(429);
  }
}
