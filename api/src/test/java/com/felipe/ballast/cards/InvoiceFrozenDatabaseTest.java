package com.felipe.ballast.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.felipe.ballast.TestcontainersConfiguration;
import com.github.dockerjava.api.DockerClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Container próprio e uma única pausa, como o teste de /contas: a primeira chamada logo depois do
// login não passa pela validação do Hikari.
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
@AutoConfigureMockMvc
@Testcontainers
class InvoiceFrozenDatabaseTest {

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    TestcontainersConfiguration.registerProperties(registry, postgres);
  }

  @Autowired MockMvc mvc;

  @Test
  @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void answers503WithinAboutTwoSecondsWhileTheDatabaseIsFrozen() throws Exception {
    InvoicesApi api = new InvoicesApi(mvc);
    InvoicesApi.Actor actor = api.newActor(15, 25);
    DockerClient docker = DockerClientFactory.instance().client();
    docker.pauseContainerCmd(postgres.getContainerId()).exec();
    try {
      long start = System.nanoTime();
      MockHttpServletResponse response =
          mvc.perform(
                  get("/contas/" + actor.cardId() + "/faturas?de=2026-03-01&ate=2026-03-31")
                      .cookie(actor.session()))
              .andReturn()
              .getResponse();
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

      assertThat(response.getStatus()).isEqualTo(503);
      assertThat(response.getContentAsString(StandardCharsets.UTF_8))
          .isEqualTo("{\"erro\":\"serviço indisponível\"}");
      assertThat(elapsed).isLessThan(Duration.ofMillis(2500));
    } finally {
      docker.unpauseContainerCmd(postgres.getContainerId()).exec();
    }
  }
}
