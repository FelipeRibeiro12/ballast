package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.felipe.ballast.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Sem ballast.registration-open: o padrão é fechado.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
@AutoConfigureMockMvc
class RegistrationClosedTest {

  private static final List<HttpMethod> METHODS =
      List.of(
          HttpMethod.GET,
          HttpMethod.HEAD,
          HttpMethod.POST,
          HttpMethod.PUT,
          HttpMethod.DELETE,
          HttpMethod.PATCH,
          HttpMethod.OPTIONS);

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;

  private MockHttpServletResponse call(HttpMethod method, String path, String contentType)
      throws Exception {
    var builder = request(method, path).content("{}");
    if (contentType != null) {
      builder.contentType(contentType);
    }
    return mvc.perform(builder).andReturn().getResponse();
  }

  private void assertSameAsUnknownRoute(
      MockHttpServletResponse closed, MockHttpServletResponse unknown) throws Exception {
    assertThat(closed.getStatus()).isEqualTo(unknown.getStatus());
    assertThat(closed.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(unknown.getContentAsString(StandardCharsets.UTF_8));
    assertThat(closed.getContentType()).isEqualTo(unknown.getContentType());
    assertThat(closed.getHeaderNames())
        .containsExactlyInAnyOrderElementsOf(unknown.getHeaderNames());
    assertThat(closed.getHeader("Allow")).isNull();
  }

  @Test
  void closedRegistrationAnswersLikeAnUnknownRouteForAnyMethodWithoutSession() throws Exception {
    for (HttpMethod method : METHODS) {
      MockHttpServletResponse closed = call(method, "/auth/cadastro", "application/json");
      MockHttpServletResponse unknown = call(method, "/auth/nao-existe", "application/json");

      assertThat(closed.getStatus()).as(method.name()).isEqualTo(401);
      assertSameAsUnknownRoute(closed, unknown);
    }
  }

  @Test
  void closedRegistrationDoesNotDependOnTheBody() throws Exception {
    MockHttpServletResponse closed = call(HttpMethod.POST, "/auth/cadastro", "text/plain");
    MockHttpServletResponse unknown = call(HttpMethod.POST, "/auth/nao-existe", "text/plain");

    assertSameAsUnknownRoute(closed, unknown);
  }

  @Test
  void closedRegistrationDoesNotCreateTheUser() throws Exception {
    String email = "t" + UUID.randomUUID().toString().replace("-", "") + "@exemplo.test";

    mvc.perform(
            post("/auth/cadastro")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"senha\":\"senha-de-teste-12345\"}"))
        .andReturn();

    int count =
        jdbc.sql("SELECT count(*) FROM usuario WHERE email = :email")
            .param("email", email)
            .query(Integer.class)
            .single();
    assertThat(count).isZero();
  }
}
