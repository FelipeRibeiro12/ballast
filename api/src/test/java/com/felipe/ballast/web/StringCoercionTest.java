package com.felipe.ballast.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Número e booleano em campo de texto são erro de tipo na borda, não texto convertido em silêncio.
@IntegrationTest
class StringCoercionTest {

  private static final String PASSWORD = "senha-de-teste-12345";

  @Autowired MockMvc mvc;

  static Stream<Arguments> scalars() {
    return Stream.of(
        Arguments.of("inteiro", "123456789012"),
        Arguments.of("decimal", "1.5"),
        Arguments.of("booleano", "true"));
  }

  private MockHttpServletResponse send(String path, String body, Cookie session) throws Exception {
    var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  @ParameterizedTest(name = "nome da conta como {0}")
  @MethodSource("scalars")
  void accountNameRejectsScalars(String kind, String scalar) throws Exception {
    TestUsers users = new TestUsers(mvc);
    Cookie session = users.session(users.register());

    MockHttpServletResponse response =
        send("/contas", "{\"nome\":" + scalar + ",\"tipo\":\"corrente\"}", session);

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @ParameterizedTest(name = "senha do login como {0}")
  @MethodSource("scalars")
  void loginPasswordRejectsScalars(String kind, String scalar) throws Exception {
    MockHttpServletResponse response =
        send("/auth/login", "{\"email\":\"a@exemplo.test\",\"senha\":" + scalar + "}", null);

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @ParameterizedTest(name = "email do login como {0}")
  @MethodSource("scalars")
  void loginEmailRejectsScalars(String kind, String scalar) throws Exception {
    MockHttpServletResponse response =
        send("/auth/login", "{\"email\":" + scalar + ",\"senha\":\"" + PASSWORD + "\"}", null);

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @ParameterizedTest(name = "senha do cadastro como {0}")
  @MethodSource("scalars")
  void registrationPasswordRejectsScalars(String kind, String scalar) throws Exception {
    MockHttpServletResponse response =
        send(
            "/auth/cadastro",
            "{\"email\":\"" + TestUsers.uniqueEmail() + "\",\"senha\":" + scalar + "}",
            null);

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @ParameterizedTest(name = "nome do cadastro como {0}")
  @MethodSource("scalars")
  void registrationNameRejectsScalars(String kind, String scalar) throws Exception {
    MockHttpServletResponse response =
        send(
            "/auth/cadastro",
            "{\"email\":\""
                + TestUsers.uniqueEmail()
                + "\",\"senha\":\""
                + PASSWORD
                + "\",\"nome\":"
                + scalar
                + "}",
            null);

    assertThat(response.getStatus()).isEqualTo(400);
  }
}
