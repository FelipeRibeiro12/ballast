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

// Container próprio: o pause congela o Postgres com o TCP aberto. Ninguém responde e ninguém
// fecha a conexão, que é o caso que os testes de banco parado e de lock não cobrem.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HealthFrozenDatabaseTest {

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    TestcontainersConfiguration.registerProperties(registry, postgres);
  }

  @Autowired MockMvc mvc;

  // A thread separada faz o teste falhar em vez de travar quando a causa não está corrigida.
  @Test
  @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void answers503WithinAboutTwoSecondsOnTheFirstAndSecondCallWhileTheDatabaseIsFrozen()
      throws Exception {
    mvc.perform(get("/healthz")).andExpect(status().isOk());
    DockerClient docker = DockerClientFactory.instance().client();
    docker.pauseContainerCmd(postgres.getContainerId()).exec();
    try {
      // Mais que os 500 ms em que o Hikari passa a validar a conexão ociosa antes de emprestá-la.
      Thread.sleep(Duration.ofSeconds(1));

      Duration first = timeHealthCheck();
      Duration second = timeHealthCheck();

      assertThat(first).as("primeira chamada").isLessThan(Duration.ofMillis(2500));
      assertThat(second).as("segunda chamada").isLessThan(Duration.ofMillis(2500));
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
