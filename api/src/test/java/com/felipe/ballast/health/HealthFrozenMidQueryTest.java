package com.felipe.ballast.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.felipe.ballast.TestcontainersConfiguration;
import com.github.dockerjava.api.DockerClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Banco congela logo depois de uma consulta: a conexão emprestada tem menos de 500 ms de ocioso,
// o Hikari não a valida, e a consulta fica esperando resposta num socket morto. O cancelamento do
// setQueryTimeout abre outra conexão, que também trava.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HealthFrozenMidQueryTest {

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    TestcontainersConfiguration.registerProperties(registry, postgres);
  }

  @Autowired MockMvc mvc;

  @Test
  @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void answers503WithinAboutTwoSecondsWhenTheDatabaseFreezesRightAfterAHealthyCall()
      throws Exception {
    mvc.perform(get("/healthz")).andExpect(status().isOk());
    DockerClient docker = DockerClientFactory.instance().client();
    docker.pauseContainerCmd(postgres.getContainerId()).exec();
    try {
      Duration elapsed = timeHealthCheck();

      assertThat(elapsed).isLessThan(Duration.ofMillis(2500));
    } finally {
      docker.unpauseContainerCmd(postgres.getContainerId()).exec();
    }
  }

  private Duration timeHealthCheck() throws Exception {
    long start = System.nanoTime();
    mvc.perform(get("/healthz"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("{\"erro\":\"indisponível\"}", true));
    return Duration.ofNanos(System.nanoTime() - start);
  }
}
