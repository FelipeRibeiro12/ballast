package com.felipe.ballast.identity;

import static com.felipe.ballast.identity.TestUsers.PASSWORD;
import static com.felipe.ballast.identity.TestUsers.SESSION_COOKIE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LoginTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private final JsonMapper json = JsonMapper.builder().build();
  private TestUsers users;

  @BeforeEach
  void setUp() {
    users = new TestUsers(mvc);
  }

  private JsonNode bodyOf(MockHttpServletResponse response) throws Exception {
    return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private MockHttpServletResponse loginRaw(String body) throws Exception {
    return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .getResponse();
  }

  @Test
  void opensASessionAndSetsTheCookieWithEveryAttribute(CapturedOutput output) throws Exception {
    TestUsers.Registered user = users.register();

    MockHttpServletResponse response = users.login(user.email(), PASSWORD);

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = bodyOf(response);
    assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "email", "nome");
    assertThat(body.get("id").asString()).isEqualTo(user.id().toString());
    assertThat(body.get("email").asString()).isEqualTo(user.email());
    assertThat(body.get("nome").asString()).isEqualTo("Fulano");

    String token = response.getCookie(SESSION_COOKIE).getValue();
    String setCookie = response.getHeader("Set-Cookie");
    assertThat(setCookie)
        .startsWith(SESSION_COOKIE + "=" + token)
        .contains("Path=/")
        .contains("Max-Age=604800")
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Lax");

    assertThat(response.getContentAsString()).doesNotContain(token).doesNotContain(PASSWORD);
    assertThat(output.getAll())
        .contains("login ok")
        .contains(user.id().toString())
        .doesNotContain(token)
        .doesNotContain(PASSWORD)
        .doesNotContain(user.email())
        .doesNotContain("argon2");
  }

  @Test
  void storesOnlyTheSha256OfAnOpaque32ByteTokenWithSevenDays() throws Exception {
    TestUsers.Registered user = users.register();

    String token = users.login(user.email(), PASSWORD).getCookie(SESSION_COOKIE).getValue();

    byte[] raw = Base64.getUrlDecoder().decode(token);
    assertThat(raw).hasSize(32);
    assertThat(token).doesNotContain("=");

    Map<String, Object> row =
        jdbc.sql("SELECT token_hash, expira_em, revogada_em FROM sessao WHERE usuario_id = :id")
            .param("id", user.id())
            .query()
            .singleRow();
    byte[] expected =
        MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    assertThat((byte[]) row.get("token_hash")).isEqualTo(expected).isNotEqualTo(raw);
    assertThat(row.get("revogada_em")).isNull();
    Instant expiresAt = ((Timestamp) row.get("expira_em")).toInstant();
    assertThat(Duration.between(Instant.now(), expiresAt))
        .isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7).plusMinutes(1));
  }

  @Test
  void eachLoginGetsANewToken() throws Exception {
    TestUsers.Registered user = users.register();

    String first = users.login(user.email(), PASSWORD).getCookie(SESSION_COOKIE).getValue();
    String second = users.login(user.email(), PASSWORD).getCookie(SESSION_COOKIE).getValue();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void unknownEmailAndWrongPasswordAreIndistinguishable() throws Exception {
    TestUsers.Registered user = users.register();

    MockHttpServletResponse wrongPassword = users.login(user.email(), "senha-errada-12345");
    MockHttpServletResponse unknownEmail =
        users.login(TestUsers.uniqueEmail(), "senha-errada-12345");

    assertThat(wrongPassword.getStatus()).isEqualTo(401);
    assertThat(unknownEmail.getStatus()).isEqualTo(401);
    assertThat(unknownEmail.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(wrongPassword.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"credenciais inválidas\"}");
    assertThat(unknownEmail.getHeaderNames())
        .containsExactlyInAnyOrderElementsOf(wrongPassword.getHeaderNames());
    assertThat(unknownEmail.getCookie(SESSION_COOKIE)).isNull();
    assertThat(wrongPassword.getCookie(SESSION_COOKIE)).isNull();
  }

  @Test
  void normalizesTheEmailCase() throws Exception {
    TestUsers.Registered user = users.register();

    MockHttpServletResponse response =
        users.login(" " + user.email().toUpperCase() + " ", PASSWORD);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void failureLogsMaskedEmailAndIpButNeverTheSecrets(CapturedOutput output) throws Exception {
    TestUsers.Registered user = users.register();

    users.login(user.email(), "senha-errada-12345");

    assertThat(output.getAll())
        .contains("login falhou")
        .contains("t***@exemplo.test")
        .contains("127.0.0.1")
        .doesNotContain(user.email())
        .doesNotContain("senha-errada-12345")
        .doesNotContain(PASSWORD);
  }

  static Stream<Arguments> invalidBodies() {
    return Stream.of(
        Arguments.of(
            "campo desconhecido",
            "{\"email\":\"a@exemplo.test\",\"senha\":\"x\",\"lembrar\":true}"),
        Arguments.of("senha ausente", "{\"email\":\"a@exemplo.test\"}"),
        Arguments.of("senha vazia", "{\"email\":\"a@exemplo.test\",\"senha\":\"\"}"),
        Arguments.of(
            "senha de 129", "{\"email\":\"a@exemplo.test\",\"senha\":\"" + "a".repeat(129) + "\"}"),
        Arguments.of("e-mail inválido", "{\"email\":\"nao-e-email\",\"senha\":\"x\"}"),
        Arguments.of("e-mail ausente", "{\"senha\":\"x\"}"),
        Arguments.of("e-mail com NUL", "{\"email\":\"a\\u0000b@exemplo.test\",\"senha\":\"x\"}"),
        Arguments.of(
            "e-mail com quebra de linha", "{\"email\":\"a\\nb@exemplo.test\",\"senha\":\"x\"}"),
        Arguments.of("json quebrado", "{"),
        Arguments.of("vazio", ""));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidBodies")
  void rejectsInvalidInputWith400(String name, String body) throws Exception {
    MockHttpServletResponse response = loginRaw(body);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(bodyOf(response).propertyNames()).containsExactly("erro");
  }

  @Test
  void shortPasswordIsJustAWrongPassword() throws Exception {
    // A política de tamanho vale só no cadastro; no login, senha curta é senha errada.
    assertThat(users.login(TestUsers.uniqueEmail(), "curta").getStatus()).isEqualTo(401);
  }

  @Test
  void rejectsAnyContentTypeButJson() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(post("/auth/login").contentType("text/plain").content("{}"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(415);
  }
}
