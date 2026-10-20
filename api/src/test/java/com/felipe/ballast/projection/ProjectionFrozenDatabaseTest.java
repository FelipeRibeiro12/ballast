package com.felipe.ballast.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
class ProjectionFrozenDatabaseTest {

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    TestcontainersConfiguration.registerProperties(registry, postgres);
  }

  @Autowired MockMvc mvc;

  @Test
  @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void answers503WithinAboutTwoSecondsWhileTheDatabaseIsFrozen() throws Exception {
    TestUsers users = new TestUsers(mvc);
    Cookie session = users.session(users.register());
    DockerClient docker = DockerClientFactory.instance().client();
    docker.pauseContainerCmd(postgres.getContainerId()).exec();
    try {
      long start = System.nanoTime();
      MockHttpServletResponse response =
          mvc.perform(get("/saldo-projetado?dias=30").cookie(session)).andReturn().getResponse();
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
