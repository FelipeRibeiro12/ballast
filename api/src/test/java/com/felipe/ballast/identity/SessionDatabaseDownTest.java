package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Container próprio: o teste derruba o banco.
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class SessionDatabaseDownTest {

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    TestcontainersConfiguration.registerProperties(registry, postgres);
  }

  @Autowired MockMvc mvc;

  private static final String UNAVAILABLE = "{\"erro\":\"serviço indisponível\"}";

  @Test
  void everyRouteAnswersAGeneric503WithoutLeakingDriverDetails(CapturedOutput output)
      throws Exception {
    TestUsers users = new TestUsers(mvc);
    Cookie session = users.session(users.register());
    postgres.stop();
    // O Hikari só valida conexão ociosa há mais de 500 ms; sem a espera, o erro seria outro.
    Thread.sleep(Duration.ofSeconds(1));

    MockHttpServletResponse response =
        mvc.perform(get("/auth/eu").cookie(session)).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(UNAVAILABLE);
    assertThat(output.getAll()).contains("sessão: banco indisponível");

    // Rotas de contas: a sessão é consultada antes, e é onde o banco fora aparece primeiro.
    MockHttpServletResponse accounts =
        mvc.perform(get("/contas").cookie(session)).andReturn().getResponse();
    assertThat(accounts.getStatus()).isEqualTo(503);
    assertThat(accounts.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(UNAVAILABLE);
    MockHttpServletResponse created =
        mvc.perform(
                post("/contas")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nome\":\"Nubank\",\"tipo\":\"corrente\"}"))
            .andReturn()
            .getResponse();
    assertThat(created.getStatus()).isEqualTo(503);

    // Sem confirmar a revogação, não dá para dizer ao cliente que saiu. Rota pública: o erro
    // chega pelo ApiExceptionHandler (JdbcClient), não pelo filtro.
    MockHttpServletResponse logout =
        mvc.perform(post("/auth/logout").cookie(session)).andReturn().getResponse();
    assertThat(logout.getStatus()).isEqualTo(503);
    assertThat(logout.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(UNAVAILABLE);
    assertThat(logout.getHeader("Set-Cookie")).isNull();

    // Caminho do JPA: a falha vem ao abrir a transação, outra exceção que a do JdbcClient.
    MockHttpServletResponse register =
        mvc.perform(
                post("/auth/cadastro")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"novo@exemplo.test\",\"senha\":\"senha-de-teste-12345\"}"))
            .andReturn()
            .getResponse();
    assertThat(register.getStatus()).isEqualTo(503);
    assertThat(register.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(UNAVAILABLE);

    assertThat(output.getAll()).doesNotContain("novo@exemplo");
  }
}
