package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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

  @Test
  void sessionCheckAnswersAGeneric500WithoutLeakingDriverDetails(CapturedOutput output)
      throws Exception {
    TestUsers users = new TestUsers(mvc);
    Cookie session = users.session(users.register());
    postgres.stop();
    // O Hikari só valida conexão ociosa há mais de 500 ms; sem a espera, o erro seria outro.
    Thread.sleep(Duration.ofSeconds(1));

    MockHttpServletResponse response =
        mvc.perform(get("/auth/eu").cookie(session)).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"erro interno\"}");
    assertThat(output.getAll()).contains("falha ao consultar a sessão");
  }
}
