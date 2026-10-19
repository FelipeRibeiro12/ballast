package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
class RegistrationTest {

  private static final String PASSWORD = "senha-de-teste-12345";

  // Os caracteres ficam no meio do texto: as bordas seriam aparadas e não provariam a validação.
  static Stream<Arguments> controlCharacters() {
    return Stream.of(
        Arguments.of("NUL", "\u0000"),
        Arguments.of("BEL", "\u0007"),
        Arguments.of("quebra de linha", "\n"),
        Arguments.of("DEL", "\u007f"),
        Arguments.of("NEL", "\u0085"),
        Arguments.of("separador de linha", "\u2028"),
        Arguments.of("separador de parágrafo", "\u2029"));
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private final JsonMapper json = JsonMapper.builder().build();

  private static String uniqueEmail() {
    return "t" + UUID.randomUUID().toString().replace("-", "") + "@exemplo.test";
  }

  private MockHttpServletResponse register(Object body) throws Exception {
    String content = body instanceof String s ? s : json.writeValueAsString(body);
    return mvc.perform(
            post("/auth/cadastro").contentType(MediaType.APPLICATION_JSON).content(content))
        .andReturn()
        .getResponse();
  }

  private JsonNode bodyOf(MockHttpServletResponse response) throws Exception {
    return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void createsTheUserAndAnswers201WithoutLeakingSecrets() throws Exception {
    String email = uniqueEmail();

    MockHttpServletResponse response =
        register(Map.of("email", email, "senha", PASSWORD, "nome", "Fulano"));

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = bodyOf(response);
    assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "email", "nome");
    assertThat(body.get("email").asString()).isEqualTo(email);
    assertThat(body.get("nome").asString()).isEqualTo("Fulano");
    UUID id = UUID.fromString(body.get("id").asString());
    assertThat(id.version()).isEqualTo(7);
    assertThat(response.getContentAsString()).doesNotContain(PASSWORD).doesNotContain("argon2");

    String storedHash =
        jdbc.sql("SELECT senha_hash FROM usuario WHERE id = :id")
            .param("id", id)
            .query(String.class)
            .single();
    assertThat(storedHash).startsWith("$argon2id$").doesNotContain(PASSWORD);
  }

  @Test
  void missingNameComesBackAsNull() throws Exception {
    MockHttpServletResponse response = register(Map.of("email", uniqueEmail(), "senha", PASSWORD));

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(bodyOf(response).get("nome").isNull()).isTrue();
  }

  @Test
  void blankNameIsStoredAsNull() throws Exception {
    MockHttpServletResponse response =
        register(Map.of("email", uniqueEmail(), "senha", PASSWORD, "nome", "   "));

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(bodyOf(response).get("nome").isNull()).isTrue();
  }

  @Test
  void normalizesTheEmailAndRejectsDuplicateWithAnotherCase(CapturedOutput output)
      throws Exception {
    String lower = uniqueEmail();
    String mixed = "  " + lower.substring(0, 3).toUpperCase() + lower.substring(3) + " ";

    MockHttpServletResponse first = register(Map.of("email", mixed, "senha", PASSWORD));
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(bodyOf(first).get("email").asString()).isEqualTo(lower);

    MockHttpServletResponse second = register(Map.of("email", lower, "senha", PASSWORD));

    assertThat(second.getStatus()).isEqualTo(409);
    assertThat(second.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"e-mail já cadastrado\"}");
    assertThat(output.getAll()).doesNotContain(lower).contains("t***@exemplo.test");
  }

  @Test
  void acceptsPasswordAtBothLimits() throws Exception {
    for (int length : new int[] {12, 128}) {
      MockHttpServletResponse response =
          register(Map.of("email", uniqueEmail(), "senha", "a".repeat(length)));
      assertThat(response.getStatus()).as("senha de %d", length).isEqualTo(201);
    }
  }

  @Test
  void passwordMayHaveControlCharacters() throws Exception {
    MockHttpServletResponse response =
        register(Map.of("email", uniqueEmail(), "senha", "senha\u0000com\u0007controle\n123"));

    assertThat(response.getStatus()).isEqualTo(201);
  }

  static Stream<Arguments> invalidBodies() {
    String longText = "a".repeat(255 - "@exemplo.test".length());
    return Stream.of(
        Arguments.of(
            "senha de 11", "{\"email\":\"a@exemplo.test\",\"senha\":\"" + "a".repeat(11) + "\"}"),
        Arguments.of(
            "senha de 129", "{\"email\":\"a@exemplo.test\",\"senha\":\"" + "a".repeat(129) + "\"}"),
        Arguments.of("senha ausente", "{\"email\":\"a@exemplo.test\"}"),
        Arguments.of("senha nula", "{\"email\":\"a@exemplo.test\",\"senha\":null}"),
        Arguments.of("e-mail ausente", "{\"senha\":\"" + PASSWORD + "\"}"),
        Arguments.of(
            "e-mail sem arroba", "{\"email\":\"exemplo.test\",\"senha\":\"" + PASSWORD + "\"}"),
        Arguments.of("e-mail sem domínio", "{\"email\":\"a@\",\"senha\":\"" + PASSWORD + "\"}"),
        Arguments.of(
            "e-mail com espaço", "{\"email\":\"a b@exemplo.test\",\"senha\":\"" + PASSWORD + "\"}"),
        Arguments.of(
            "e-mail de 255",
            "{\"email\":\"" + longText + "@exemplo.test\",\"senha\":\"" + PASSWORD + "\"}"),
        Arguments.of(
            "nome de 101",
            "{\"email\":\"a@exemplo.test\",\"senha\":\""
                + PASSWORD
                + "\",\"nome\":\""
                + "a".repeat(101)
                + "\"}"),
        Arguments.of(
            "campo desconhecido",
            "{\"email\":\"a@exemplo.test\",\"senha\":\"" + PASSWORD + "\",\"admin\":true}"),
        Arguments.of("tipo errado", "{\"email\":1,\"senha\":\"" + PASSWORD + "\"}"),
        Arguments.of("json quebrado", "{\"email\":"),
        Arguments.of(
            "dois documentos", "{\"email\":\"a@exemplo.test\",\"senha\":\"" + PASSWORD + "\"}{}"),
        Arguments.of("vazio", ""),
        Arguments.of("array", "[]"));
  }

  @ParameterizedTest
  @MethodSource("invalidBodies")
  void rejectsInvalidInputWith400AndAGenericBody(String name, String body) throws Exception {
    MockHttpServletResponse response = register(body);

    assertThat(response.getStatus()).isEqualTo(400);
    JsonNode error = bodyOf(response);
    assertThat(error.propertyNames()).containsExactly("erro");
    assertThat(error.get("erro").asString()).isNotBlank();
  }

  @ParameterizedTest
  @MethodSource("controlCharacters")
  void rejectsControlCharactersInTheEmailBeforeTheDatabase(String characterName, String control)
      throws Exception {
    String email = "ab" + control + "cd@exemplo.test";

    MockHttpServletResponse response = register(Map.of("email", email, "senha", PASSWORD));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain(email);
  }

  @ParameterizedTest
  @MethodSource("controlCharacters")
  void rejectsControlCharactersInTheName(String characterName, String control) throws Exception {
    String name = "Fu" + control + "lano";

    MockHttpServletResponse response =
        register(Map.of("email", uniqueEmail(), "senha", PASSWORD, "nome", name));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain(name);
  }

  @ParameterizedTest
  @ValueSource(strings = {"text/plain", "application/x-www-form-urlencoded", "application/xml"})
  void rejectsAnyContentTypeButJson(String contentType) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(post("/auth/cadastro").contentType(contentType).content("{}"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(415);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"content-type deve ser application/json\"}");
  }

  @Test
  void rejectsMissingContentType() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(post("/auth/cadastro").content("{}")).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(415);
  }

  @Test
  void acceptsCharsetInTheContentType() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/auth/cadastro")
                    .contentType("application/json; charset=utf-8")
                    .content(
                        json.writeValueAsString(Map.of("email", uniqueEmail(), "senha", PASSWORD))))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(201);
  }
}
