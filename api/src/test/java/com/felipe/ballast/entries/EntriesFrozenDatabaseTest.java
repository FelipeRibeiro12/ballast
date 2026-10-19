package com.felipe.ballast.entries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.TestcontainersConfiguration;
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

// Container próprio e uma única pausa, como em AccountsFrozenDatabaseTest: a primeira chamada
// autenticada depois do congelamento não pode esperar o socketTimeout de 30 s. O corpo do POST é
// válido de propósito, para a rota chegar ao banco (conferência de conta) e não parar na validação.
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
@AutoConfigureMockMvc
@Testcontainers
class EntriesFrozenDatabaseTest {

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    TestcontainersConfiguration.registerProperties(registry, postgres);
  }

  @Autowired MockMvc mvc;

  private void assertUnavailableQuickly(MockHttpServletResponse response, Duration elapsed)
      throws Exception {
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"serviço indisponível\"}");
    assertThat(elapsed).isLessThan(Duration.ofMillis(2500));
  }

  @Test
  @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void entryRoutesAnswer503WithinAboutTwoSecondsWhileTheDatabaseIsFrozen() throws Exception {
    EntriesApi api = new EntriesApi(mvc);
    EntriesApi.Actor actor = api.newActor();
    Cookie session = actor.session();
    String body = EntriesApi.JSON.writeValueAsString(EntriesApi.expenseBody(actor));
    DockerClient docker = DockerClientFactory.instance().client();
    docker.pauseContainerCmd(postgres.getContainerId()).exec();
    try {
      long start = System.nanoTime();
      MockHttpServletResponse first =
          mvc.perform(
                  post("/lancamentos")
                      .cookie(session)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(body))
              .andReturn()
              .getResponse();
      assertUnavailableQuickly(first, Duration.ofNanos(System.nanoTime() - start));

      start = System.nanoTime();
      MockHttpServletResponse list =
          mvc.perform(get("/lancamentos?de=2026-03-01&ate=2026-03-31").cookie(session))
              .andReturn()
              .getResponse();
      assertUnavailableQuickly(list, Duration.ofNanos(System.nanoTime() - start));

      start = System.nanoTime();
      MockHttpServletResponse removed =
          mvc.perform(delete("/lancamentos/" + EntriesApi.randomId()).cookie(session))
              .andReturn()
              .getResponse();
      assertUnavailableQuickly(removed, Duration.ofNanos(System.nanoTime() - start));

      start = System.nanoTime();
      MockHttpServletResponse categories =
          mvc.perform(get("/categorias").cookie(session)).andReturn().getResponse();
      assertUnavailableQuickly(categories, Duration.ofNanos(System.nanoTime() - start));
    } finally {
      docker.unpauseContainerCmd(postgres.getContainerId()).exec();
    }
  }
}
