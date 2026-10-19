package com.felipe.ballast.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.felipe.ballast.TestcontainersConfiguration;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Sem springdoc.api-docs.enabled (a configuração padrão, a de produção) a spec não sai, nem
// para quem tem sessão.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
@AutoConfigureMockMvc
class ApiDocsDisabledTest {

  static Stream<Arguments> paths() {
    return Stream.of(
        Arguments.of("/v3/api-docs"),
        Arguments.of("/v3/api-docs.yaml"),
        Arguments.of("/v3/api-docs/swagger-config"));
  }

  @Autowired MockMvc mvc;

  @ParameterizedTest(name = "{0}")
  @MethodSource("paths")
  void anonymousRequestDoesNotGetTheSpec(String path) throws Exception {
    MockHttpServletResponse response = mvc.perform(get(path)).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"não autenticado\"}");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("paths")
  void evenALoggedInUserDoesNotGetTheSpec(String path) throws Exception {
    TestUsers users = new TestUsers(mvc);
    Cookie session = users.session(users.register());

    MockHttpServletResponse response =
        mvc.perform(get(path).cookie(session)).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("openapi");
  }
}
