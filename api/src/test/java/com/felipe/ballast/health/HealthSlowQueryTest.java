package com.felipe.ballast.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// Container próprio: o lock exclusivo na tabela não pode vazar para os outros testes.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HealthSlowQueryTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

  @Autowired MockMvc mvc;

  // Sem o limite da consulta o MockMvc fica pendurado; a thread separada faz o teste falhar em vez
  // de travar.
  @Test
  @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void answers503WhenTheQueryHangsOnALock() throws Exception {
    try (Connection locker =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement statement = locker.createStatement()) {
      locker.setAutoCommit(false);
      statement.execute("LOCK TABLE health_check IN ACCESS EXCLUSIVE MODE");

      long start = System.nanoTime();
      mvc.perform(get("/healthz"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(content().json("{\"erro\":\"indisponível\"}", true));
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

      // A conexão sai do pool na hora; só o limite da consulta (2 s) pode demorar.
      assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
      locker.rollback();
    }
  }
}
