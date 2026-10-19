package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.TestcontainersConfiguration;
import com.felipe.ballast.identity.TestUsers;
import com.github.dockerjava.api.DockerClient;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Container próprio e uma única pausa: mede a PRIMEIRA chamada autenticada depois do
// congelamento, logo após o login e sem espera. A conexão usada há menos de 500 ms não é validada
// pelo Hikari (aliveBypassWindow), então a consulta de sessão vai direto a um socket mudo.
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
@AutoConfigureMockMvc
@Testcontainers
class AccountsFrozenDatabaseTest {

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    TestcontainersConfiguration.registerProperties(registry, postgres);
  }

  @Autowired MockMvc mvc;

  // A thread separada faz o teste falhar em vez de travar quando a causa não está corrigida.
  @Test
  @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void firstAuthenticatedCallAnswers503WithinAboutTwoSecondsWhileTheDatabaseIsFrozen()
      throws Exception {
    TestUsers users = new TestUsers(mvc);
    Cookie session = users.session(users.register());
    DockerClient docker = DockerClientFactory.instance().client();
    docker.pauseContainerCmd(postgres.getContainerId()).exec();
    try {
      long start = System.nanoTime();
      MockHttpServletResponse first =
          mvc.perform(get("/contas").cookie(session)).andReturn().getResponse();
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

      assertThat(first.getStatus()).isEqualTo(503);
      assertThat(first.getContentAsString(StandardCharsets.UTF_8))
          .isEqualTo("{\"erro\":\"serviço indisponível\"}");
      assertThat(elapsed).as("primeira chamada").isLessThan(Duration.ofMillis(2500));

      long startCreate = System.nanoTime();
      MockHttpServletResponse created =
          mvc.perform(
                  post("/contas")
                      .cookie(session)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"nome\":\"Nubank\",\"tipo\":\"corrente\"}"))
              .andReturn()
              .getResponse();
      assertThat(created.getStatus()).isEqualTo(503);
      assertThat(Duration.ofNanos(System.nanoTime() - startCreate))
          .as("segunda chamada")
          .isLessThan(Duration.ofMillis(2500));
    } finally {
      docker.unpauseContainerCmd(postgres.getContainerId()).exec();
    }
  }
}
