package com.felipe.ballast.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.BallastApplication;
import com.felipe.ballast.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Sobe a aplicação de verdade, com o log de arranque capturado: um contexto de teste reaproveitado
// já teria subido antes e o log não estaria mais lá para conferir.
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class NoGeneratedPasswordTest {

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  @Test
  void startupDoesNotCreateNorLogAGeneratedUser(CapturedOutput output) {
    String[] args = {
      "--server.port=0",
      "--spring.datasource.url=" + postgres.getJdbcUrl(),
      "--spring.datasource.username=" + TestcontainersConfiguration.APP_USER,
      "--spring.datasource.password=" + TestcontainersConfiguration.APP_PASSWORD,
      "--spring.flyway.user=" + postgres.getUsername(),
      "--spring.flyway.password=" + postgres.getPassword(),
      "--spring.flyway.placeholders.app_user=" + TestcontainersConfiguration.APP_USER,
      "--ballast.password.memory-kib=8",
      "--ballast.password.iterations=1",
      "--ballast.password.parallelism=1",
      "--ballast.recurrence.job-enabled=false"
    };

    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(BallastApplication.class).run(args)) {
      assertThat(context.getBeansOfType(InMemoryUserDetailsManager.class)).isEmpty();
    }

    assertThat(output.getAll()).contains("Tomcat started on port");
    assertThat(output.getAll()).doesNotContainIgnoringCase("generated security password");
  }
}
