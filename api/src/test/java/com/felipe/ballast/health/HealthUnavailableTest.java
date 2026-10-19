package com.felipe.ballast.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.felipe.ballast.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Container próprio: este teste derruba o banco, e o container compartilhado
// dos outros testes não pode ser afetado.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HealthUnavailableTest {

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    TestcontainersConfiguration.registerProperties(registry, postgres);
  }

  @Autowired MockMvc mvc;

  @Test
  void answers503QuicklyAndHidesDriverDetailsWhenDatabaseIsDown() throws Exception {
    mvc.perform(get("/healthz")).andExpect(status().isOk());
    postgres.stop();
    // O Hikari só valida conexão ociosa há mais de 500 ms. Sem a espera, a primeira
    // requisição quebra numa conexão morta e nunca chega a esperar por uma nova.
    Thread.sleep(Duration.ofSeconds(1));

    long start = System.nanoTime();
    mvc.perform(get("/healthz"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("{\"erro\":\"indisponível\"}", true));
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    // O padrão do Hikari é 30 s; a folga cobre a validação da conexão morta.
    assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
  }
}
