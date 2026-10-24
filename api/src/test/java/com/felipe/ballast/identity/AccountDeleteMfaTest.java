package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.crypto.CryptoApi;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// ADR-053: com o MFA ligado, apagar conta pede o código no corpo do DELETE.
@IntegrationTest
class AccountDeleteMfaTest {

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;

  private CryptoApi api;
  private Cookie session;
  private String accountId;
  private byte[] secret;
  private List<String> recoveryCodes;

  @BeforeEach
  void setUp() throws Exception {
    long step = Totp.stepOf(Instant.now()) + 1;
    clock.freeze(Instant.ofEpochSecond(step * 30));
    api = new CryptoApi(mvc);
    session = api.newActor().session();
    accountId = api.createAccountOk(session, "Poupança", "poupanca");
    enableMfa();
    clock.advance(Duration.ofSeconds(30));
  }

  @AfterEach
  void resetClock() {
    clock.reset();
  }

  private void enableMfa() throws Exception {
    JsonNode setup =
        CryptoApi.expect(
            api.send(session, post("/auth/mfa/configurar"), Map.of("senha", TestUsers.PASSWORD)),
            200);
    secret = MfaTest.decodeBase32(setup.get("segredo").asString());
    JsonNode activation =
        CryptoApi.expect(
            api.send(session, post("/auth/mfa/ativar"), Map.of("codigo", codeNow())), 200);
    recoveryCodes = new ArrayList<>();
    activation.get("codigosDeRecuperacao").forEach(code -> recoveryCodes.add(code.asString()));
  }

  private String codeNow() {
    return Totp.codeAt(secret, Totp.stepOf(clock.instant()));
  }

  private String wrongCode() {
    return codeNow().equals("000000") ? "111111" : "000000";
  }

  private MockHttpServletResponse remove(String id, Object body) throws Exception {
    return api.send(session, delete("/contas/" + id), body);
  }

  private boolean exists(String id) throws Exception {
    return api.send(session, get("/contas/" + id), null).getStatus() == 200;
  }

  @Test
  void withoutACodeNothingIsDeleted() throws Exception {
    MockHttpServletResponse response = remove(accountId, null);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(response)).isEqualTo("{\"erro\":\"código inválido\"}");
    assertThat(exists(accountId)).isTrue();
  }

  @Test
  void aWrongCodeDeletesNothing() throws Exception {
    MockHttpServletResponse response = remove(accountId, Map.of("codigo", wrongCode()));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(response)).isEqualTo("{\"erro\":\"código inválido\"}");
    assertThat(exists(accountId)).isTrue();
  }

  @Test
  void theAuthenticatorCodeDeletes() throws Exception {
    MockHttpServletResponse response = remove(accountId, Map.of("codigo", codeNow()));

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(exists(accountId)).isFalse();
  }

  @Test
  void aRecoveryCodeDeletesAndIsSpent() throws Exception {
    MockHttpServletResponse response = remove(accountId, Map.of("codigo", recoveryCodes.get(0)));

    assertThat(response.getStatus()).isEqualTo(204);
    JsonNode status = CryptoApi.body(api.send(session, get("/auth/mfa"), null));
    assertThat(status.get("codigosRestantes").asInt()).isEqualTo(9);
  }

  @Test
  void theSameAuthenticatorCodeDoesNotWorkTwice() throws Exception {
    String other = api.createAccountOk(session, "Outra", "poupanca");
    String code = codeNow();
    assertThat(remove(accountId, Map.of("codigo", code)).getStatus()).isEqualTo(204);

    assertThat(remove(other, Map.of("codigo", code)).getStatus()).isEqualTo(400);
    assertThat(exists(other)).isTrue();
  }

  @Test
  void wrongCodesDelayTheUserAndTheRightOneWaits() throws Exception {
    for (int i = 0; i < 4; i++) {
      assertThat(remove(accountId, Map.of("codigo", wrongCode())).getStatus()).isEqualTo(400);
    }

    MockHttpServletResponse delayed = remove(accountId, Map.of("codigo", codeNow()));

    assertThat(delayed.getStatus()).isEqualTo(429);
    assertThat(delayed.getHeader("Retry-After")).isEqualTo("2");
    assertThat(CryptoApi.text(delayed))
        .isEqualTo("{\"erro\":\"muitas tentativas, tente mais tarde\"}");
    assertThat(exists(accountId)).isTrue();
  }

  // O código certo do dono não abre a conta de outro: o dono do recurso continua sendo a sessão.
  @Test
  void anotherUsersAccountIsStill404WithAValidCode() throws Exception {
    CryptoApi.Actor other = api.newActor();

    MockHttpServletResponse response = remove(other.checkingId(), Map.of("codigo", codeNow()));

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(api.send(other.session(), get("/contas/" + other.checkingId()), null).getStatus())
        .isEqualTo(200);
  }

  @Test
  void withoutMfaTheDeleteStillNeedsNoBody() throws Exception {
    CryptoApi.Actor plain = api.newActor();
    String id = api.createAccountOk(plain.session(), "Poupança", "poupanca");

    MockHttpServletResponse response = api.send(plain.session(), delete("/contas/" + id), null);

    assertThat(response.getStatus()).isEqualTo(204);
  }

  @Test
  void aCrossSiteOriginIsStillRefusedBeforeTheCodeIsChecked() throws Exception {
    MockHttpServletResponse response =
        api.send(
            session,
            delete("/contas/" + accountId).header("Origin", "https://evil.example"),
            Map.of("codigo", codeNow()));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(exists(accountId)).isTrue();
  }

  @Test
  void theCodeFieldHasALimit() throws Exception {
    MockHttpServletResponse response = remove(accountId, Map.of("codigo", "1".repeat(33)));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(exists(accountId)).isTrue();
  }
}
