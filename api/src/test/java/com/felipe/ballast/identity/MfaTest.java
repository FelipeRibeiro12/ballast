package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class MfaTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String MFA_COOKIE = "__Host-ballast_mfa";

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  @Autowired JdbcClient jdbc;

  private TestUsers users;
  private TestUsers.Registered user;
  private Cookie session;

  @BeforeEach
  void setUp() throws Exception {
    // Começo de um passo de 30 s: o código de agora vale até o teste avançar o relógio.
    long step = Totp.stepOf(Instant.now()) + 1;
    clock.freeze(Instant.ofEpochSecond(step * 30));
    users = new TestUsers(mvc);
    user = users.register();
    session = users.session(user);
  }

  @AfterEach
  void resetClock() {
    clock.reset();
  }

  private MockHttpServletResponse send(String path, Map<String, String> body, Cookie... cookies)
      throws Exception {
    var request =
        post(path).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    if (cookies.length > 0) {
      request.cookie(cookies);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private static String error(MockHttpServletResponse response) throws Exception {
    return body(response).get("erro").asString();
  }

  // Base32 da RFC 4648, só para o teste fazer o papel do autenticador.
  private static byte[] decodeBase32(String text) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (char c : text.toCharArray()) {
      buffer = (buffer << 5) | alphabet.indexOf(c);
      bits += 5;
      if (bits >= 8) {
        out.write((buffer >> (bits - 8)) & 0xff);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }

  private String codeNow(byte[] secret) {
    return Totp.codeAt(secret, Totp.stepOf(clock.instant()));
  }

  private byte[] configure() throws Exception {
    MockHttpServletResponse response =
        send("/auth/mfa/configurar", Map.of("senha", TestUsers.PASSWORD), session);
    assertThat(response.getStatus()).isEqualTo(200);
    return decodeBase32(body(response).get("segredo").asString());
  }

  private List<String> activate(byte[] secret) throws Exception {
    MockHttpServletResponse response =
        send("/auth/mfa/ativar", Map.of("codigo", codeNow(secret)), session);
    assertThat(response.getStatus()).isEqualTo(200);
    List<String> codes = new ArrayList<>();
    body(response).get("codigosDeRecuperacao").forEach(code -> codes.add(code.asString()));
    return codes;
  }

  private Cookie challenge() throws Exception {
    MockHttpServletResponse response = users.login(user.email(), TestUsers.PASSWORD);
    assertThat(response.getStatus()).isEqualTo(202);
    return response.getCookie(MFA_COOKIE);
  }

  private MockHttpServletResponse answer(Cookie challenge, String code) throws Exception {
    return send("/auth/login/mfa", Map.of("codigo", code), challenge);
  }

  private JsonNode status() throws Exception {
    return body(mvc.perform(get("/auth/mfa").cookie(session)).andReturn().getResponse());
  }

  @Test
  void startsOff() throws Exception {
    assertThat(status().toString()).isEqualTo("{\"ativo\":false,\"codigosRestantes\":0}");
  }

  @Test
  void configureNeedsTheRightPassword() throws Exception {
    MockHttpServletResponse wrong =
        send("/auth/mfa/configurar", Map.of("senha", "senha-errada-123"), session);

    assertThat(wrong.getStatus()).isEqualTo(400);
    assertThat(error(wrong)).isEqualTo("senha incorreta");
  }

  @Test
  void configureGivesTheSecretAndTheOtpauthUri() throws Exception {
    MockHttpServletResponse response =
        send("/auth/mfa/configurar", Map.of("senha", TestUsers.PASSWORD), session);

    String secret = body(response).get("segredo").asString();
    assertThat(secret).matches("[A-Z2-7]{32}");
    assertThat(body(response).get("uri").asString())
        .isEqualTo(
            "otpauth://totp/Ballast:"
                + user.email().replace("@", "%40")
                + "?secret="
                + secret
                + "&issuer=Ballast&algorithm=SHA1&digits=6&period=30");
    assertThat(status().get("ativo").asBoolean()).isFalse();
  }

  @Test
  void secretIsEncryptedAtRest() throws Exception {
    byte[] secret = configure();

    byte[] stored =
        jdbc.sql("SELECT segredo_cifrado FROM mfa WHERE usuario_id = :id")
            .param("id", user.id())
            .query(byte[].class)
            .single();

    assertThat(stored).hasSize(48);
    assertThat(new String(stored, StandardCharsets.ISO_8859_1))
        .doesNotContain(new String(secret, StandardCharsets.ISO_8859_1));
  }

  @Test
  void activateWithTheCurrentCodeGivesTenRecoveryCodes() throws Exception {
    List<String> codes = activate(configure());

    assertThat(codes).hasSize(10).allMatch(code -> code.matches("[a-z2-9]{5}-[a-z2-9]{5}"));
    assertThat(new HashSet<>(codes)).hasSize(10);
    assertThat(status().toString()).isEqualTo("{\"ativo\":true,\"codigosRestantes\":10}");
  }

  @Test
  void recoveryCodesAreStoredOnlyAsHash() throws Exception {
    List<String> codes = activate(configure());

    List<byte[]> stored =
        jdbc.sql("SELECT codigo_hash FROM mfa_recuperacao WHERE usuario_id = :id")
            .param("id", user.id())
            .query(byte[].class)
            .list();

    assertThat(stored).hasSize(10).allMatch(hash -> hash.length == 32);
    for (byte[] hash : stored) {
      assertThat(new String(hash, StandardCharsets.ISO_8859_1))
          .doesNotContain(codes.get(0).replace("-", ""));
    }
  }

  @Test
  void activateWithAWrongCodeKeepsItOff() throws Exception {
    byte[] secret = configure();
    String wrong = codeNow(secret).equals("000000") ? "111111" : "000000";

    MockHttpServletResponse response = send("/auth/mfa/ativar", Map.of("codigo", wrong), session);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(error(response)).isEqualTo("código inválido");
    assertThat(status().get("ativo").asBoolean()).isFalse();
  }

  @Test
  void activateWithoutConfiguringIsAConflict() throws Exception {
    MockHttpServletResponse response =
        send("/auth/mfa/ativar", Map.of("codigo", "123456"), session);

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(error(response)).isEqualTo("configure o mfa antes");
  }

  @Test
  void configureAgainWhileOnIsAConflict() throws Exception {
    activate(configure());

    MockHttpServletResponse response =
        send("/auth/mfa/configurar", Map.of("senha", TestUsers.PASSWORD), session);

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(error(response)).isEqualTo("mfa já ativo");
  }

  @Test
  void loginWithMfaOnAsksForTheCodeWithoutOpeningASession() throws Exception {
    activate(configure());

    MockHttpServletResponse response = users.login(user.email(), TestUsers.PASSWORD);

    assertThat(response.getStatus()).isEqualTo(202);
    assertThat(body(response).toString()).isEqualTo("{\"mfaPendente\":true}");
    assertThat(response.getCookie(TestUsers.SESSION_COOKIE)).isNull();
    Cookie challenge = response.getCookie(MFA_COOKIE);
    assertThat(challenge.isHttpOnly()).isTrue();
    assertThat(challenge.getMaxAge()).isEqualTo(300);
  }

  @Test
  void theCodeOpensTheSessionAndExpiresTheChallengeCookie() throws Exception {
    byte[] secret = configure();
    activate(secret);
    clock.advance(Duration.ofSeconds(30));
    Cookie challenge = challenge();

    MockHttpServletResponse response = answer(challenge, codeNow(secret));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(body(response).get("email").asString()).isEqualTo(user.email());
    Cookie opened = response.getCookie(TestUsers.SESSION_COOKIE);
    assertThat(opened.getValue()).isNotEmpty();
    assertThat(response.getCookie(MFA_COOKIE).getMaxAge()).isZero();
    assertThat(mvc.perform(get("/auth/eu").cookie(opened)).andReturn().getResponse().getStatus())
        .isEqualTo(200);
  }

  @Test
  void theChallengeOpensOnlyOneSession() throws Exception {
    byte[] secret = configure();
    activate(secret);
    clock.advance(Duration.ofSeconds(30));
    Cookie challenge = challenge();
    answer(challenge, codeNow(secret));
    clock.advance(Duration.ofSeconds(30));

    MockHttpServletResponse again = answer(challenge, codeNow(secret));

    assertThat(again.getStatus()).isEqualTo(401);
    assertThat(error(again)).isEqualTo("desafio expirado ou inválido");
  }

  // Quem viu o código por cima do ombro não entra com ele depois do dono.
  @Test
  void theSameCodeDoesNotWorkTwice() throws Exception {
    byte[] secret = configure();
    activate(secret);
    clock.advance(Duration.ofSeconds(30));
    String code = codeNow(secret);
    answer(challenge(), code);

    MockHttpServletResponse replay = answer(challenge(), code);

    assertThat(replay.getStatus()).isEqualTo(401);
    assertThat(error(replay)).isEqualTo("código inválido");
  }

  @Test
  void theActivationCodeDoesNotOpenALogin() throws Exception {
    byte[] secret = configure();
    String code = codeNow(secret);
    send("/auth/mfa/ativar", Map.of("codigo", code), session);

    assertThat(answer(challenge(), code).getStatus()).isEqualTo(401);
  }

  @Test
  void aRecoveryCodeWorksOnceInAnyCase() throws Exception {
    List<String> codes = activate(configure());
    String code = codes.get(3);

    MockHttpServletResponse first =
        answer(challenge(), "  " + code.toUpperCase().replace("-", "") + " ");
    MockHttpServletResponse second = answer(challenge(), code);

    assertThat(first.getStatus()).isEqualTo(200);
    assertThat(second.getStatus()).isEqualTo(401);
    assertThat(status().get("codigosRestantes").asInt()).isEqualTo(9);
  }

  @Test
  void challengeExpiresAfterFiveMinutes() throws Exception {
    byte[] secret = configure();
    activate(secret);
    Cookie challenge = challenge();
    clock.advance(Duration.ofMinutes(5));

    MockHttpServletResponse response = answer(challenge, codeNow(secret));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(error(response)).isEqualTo("desafio expirado ou inválido");
  }

  @Test
  void withoutTheChallengeCookieThereIsNothingToAnswer() throws Exception {
    MockHttpServletResponse response =
        send("/auth/login/mfa", Map.of("codigo", "123456"), new Cookie("outro", "x"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(error(response)).isEqualTo("desafio expirado ou inválido");
  }

  // Seis dígitos caem rápido sem limite (docs/ameacas.md). O atraso é por usuário, como o do
  // login: trocar de IP ou de desafio não zera a conta.
  @Test
  void wrongCodesDelayTheUserEvenWithANewChallenge() throws Exception {
    byte[] secret = configure();
    activate(secret);
    clock.advance(Duration.ofSeconds(30));
    String right = codeNow(secret);
    String wrong = right.equals("000000") ? "111111" : "000000";
    for (int i = 0; i < 3; i++) {
      assertThat(answer(challenge(), wrong).getStatus()).isEqualTo(401);
    }
    assertThat(answer(challenge(), wrong).getStatus()).isEqualTo(401);

    MockHttpServletResponse delayed = answer(challenge(), right);

    assertThat(delayed.getStatus()).isEqualTo(429);
    assertThat(delayed.getHeader("Retry-After")).isEqualTo("2");
    assertThat(error(delayed)).isEqualTo("muitas tentativas, tente mais tarde");

    clock.advance(Duration.ofSeconds(2));
    assertThat(answer(challenge(), right).getStatus()).isEqualTo(200);
  }

  @Test
  void disableNeedsPasswordAndCodeAndThenLoginOpensDirectly() throws Exception {
    byte[] secret = configure();
    activate(secret);
    clock.advance(Duration.ofSeconds(30));

    MockHttpServletResponse wrongPassword =
        send(
            "/auth/mfa/desativar",
            Map.of("senha", "senha-errada-123", "codigo", codeNow(secret)),
            session);
    MockHttpServletResponse disabled =
        send(
            "/auth/mfa/desativar",
            Map.of("senha", TestUsers.PASSWORD, "codigo", codeNow(secret)),
            session);

    assertThat(wrongPassword.getStatus()).isEqualTo(400);
    assertThat(error(wrongPassword)).isEqualTo("senha incorreta");
    assertThat(disabled.getStatus()).isEqualTo(204);
    assertThat(status().toString()).isEqualTo("{\"ativo\":false,\"codigosRestantes\":0}");
    assertThat(users.login(user.email(), TestUsers.PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void disableWithAWrongCodeKeepsItOn() throws Exception {
    byte[] secret = configure();
    activate(secret);
    clock.advance(Duration.ofSeconds(30));
    String wrong = codeNow(secret).equals("000000") ? "111111" : "000000";

    MockHttpServletResponse response =
        send("/auth/mfa/desativar", Map.of("senha", TestUsers.PASSWORD, "codigo", wrong), session);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(error(response)).isEqualTo("código inválido");
    assertThat(status().get("ativo").asBoolean()).isTrue();
  }

  @Test
  void disableWhenOffIsAConflict() throws Exception {
    MockHttpServletResponse response =
        send(
            "/auth/mfa/desativar",
            Map.of("senha", TestUsers.PASSWORD, "codigo", "123456"),
            session);

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(error(response)).isEqualTo("mfa não está ativo");
  }

  @Test
  void codeFieldHasALimit() throws Exception {
    configure();

    MockHttpServletResponse response =
        send("/auth/mfa/ativar", Map.of("codigo", "1".repeat(33)), session);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(error(response)).isEqualTo("código inválido");
  }

  @Test
  void routesNeedASession() throws Exception {
    assertThat(mvc.perform(get("/auth/mfa")).andReturn().getResponse().getStatus()).isEqualTo(401);
    for (String path : List.of("/auth/mfa/configurar", "/auth/mfa/ativar", "/auth/mfa/desativar")) {
      assertThat(send(path, Map.of("senha", TestUsers.PASSWORD, "codigo", "123456")).getStatus())
          .as(path)
          .isEqualTo(401);
    }
  }

  // Cada um com o seu segredo: o código de A não responde o desafio de B, e o MFA de A não
  // aparece para B.
  @Test
  void codesOfOneUserDoNotAnswerTheChallengeOfAnother() throws Exception {
    byte[] secretA = configure();
    activate(secretA);
    TestUsers.Registered other = users.register();
    Cookie otherSession = users.session(other);
    MockHttpServletResponse otherStatus =
        mvc.perform(get("/auth/mfa").cookie(otherSession)).andReturn().getResponse();
    assertThat(body(otherStatus).get("ativo").asBoolean()).isFalse();
    MockHttpServletResponse setup =
        send("/auth/mfa/configurar", Map.of("senha", TestUsers.PASSWORD), otherSession);
    byte[] secretB = decodeBase32(body(setup).get("segredo").asString());
    send("/auth/mfa/ativar", Map.of("codigo", codeNow(secretB)), otherSession);
    clock.advance(Duration.ofSeconds(30));
    Cookie challengeB = users.login(other.email(), TestUsers.PASSWORD).getCookie(MFA_COOKIE);

    assertThat(answer(challengeB, codeNow(secretA)).getStatus()).isEqualTo(401);
    assertThat(answer(challengeB, codeNow(secretB)).getStatus()).isEqualTo(200);
  }
}
