package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

// E-mail é a identidade do login: o que o Java grava tem que ser o que o Postgres chama de
// minúsculo (CHECK email = lower(email)), e caractere invisível não pode criar um segundo "igual".
@IntegrationTest
class EmailNormalizationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;

  private MockHttpServletResponse send(String path, String email) throws Exception {
    // Login recusa campo que não declara, então o nome só vai no cadastro.
    Map<String, String> fields =
        path.endsWith("cadastro")
            ? Map.of("email", email, "senha", TestUsers.PASSWORD, "nome", "F")
            : Map.of("email", email, "senha", TestUsers.PASSWORD);
    String body = JSON.writeValueAsString(fields);
    return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .getResponse();
  }

  @ParameterizedTest(name = "U+{0}")
  @ValueSource(strings = {"200B", "202E", "FEFF", "200D", "200C", "00AD", "2060"})
  void formatCharacterInTheEmailIsRejectedOnRegistrationAndLogin(String hex) throws Exception {
    String invisible = new String(Character.toChars(Integer.parseInt(hex, 16)));
    String email = "fulano" + invisible + "@exemplo.test";

    assertThat(send("/auth/cadastro", email).getStatus()).isEqualTo(400);
    assertThat(send("/auth/login", email).getStatus()).isEqualTo(400);
  }

  @Test
  void dottedCapitalIIsRejectedBecauseJavaAndPostgresDisagreeOnItsLowercase() throws Exception {
    // Java: "i" + U+0307. Postgres (lower por code point): "i". Os dois nomes viram identidades
    // diferentes conforme quem normaliza, então a borda recusa.
    String email = "İlker@exemplo.test";

    assertThat(send("/auth/cadastro", email).getStatus()).isEqualTo(400);
    assertThat(send("/auth/login", email).getStatus()).isEqualTo(400);
  }

  @Test
  void postgresLowercaseOfDottedCapitalIDiffersFromJava() {
    String javaLower = "İ".toLowerCase(Locale.ROOT);
    String postgres =
        jdbc.sql("SELECT lower(CAST(:s AS text))").param("s", "İ").query(String.class).single();

    assertThat(postgres).isNotEqualTo(javaLower);
  }

  @Test
  void fullwidthLettersFoldToAsciiBeforeLowercasing() throws Exception {
    String suffix = TestUsers.uniqueEmail();
    String ascii = "fw" + suffix;
    String fullwidth = "ＦＷ" + suffix.toUpperCase(Locale.ROOT);

    assertThat(send("/auth/cadastro", fullwidth).getStatus()).isEqualTo(201);

    // Entrou como ascii minúsculo: o login pelo e-mail comum acha a conta.
    assertThat(send("/auth/login", ascii).getStatus()).isEqualTo(200);
  }

  @Test
  void normalizedEmailPassesTheDatabaseLowercaseCheck() throws Exception {
    String email = TestUsers.uniqueEmail().toUpperCase(Locale.ROOT);

    assertThat(send("/auth/cadastro", email).getStatus()).isEqualTo(201);
  }
}
