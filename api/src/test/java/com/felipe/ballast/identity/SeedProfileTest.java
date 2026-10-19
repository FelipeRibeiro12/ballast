package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.BallastApplication;
import com.felipe.ballast.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Sobe a aplicação como o `make seed` sobe: perfil seed, sem servidor web, só o ambiente.
@Testcontainers
class SeedProfileTest {

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  @Test
  void seedProfileCreatesTheUserWithoutStartingTheWebServer() {
    String email = "t" + UUID.randomUUID().toString().replace("-", "") + "@exemplo.test";
    Map<String, Object> properties =
        Map.ofEntries(
            Map.entry("spring.datasource.url", postgres.getJdbcUrl()),
            Map.entry("spring.datasource.username", TestcontainersConfiguration.APP_USER),
            Map.entry("spring.datasource.password", TestcontainersConfiguration.APP_PASSWORD),
            Map.entry("spring.flyway.user", postgres.getUsername()),
            Map.entry("spring.flyway.password", postgres.getPassword()),
            Map.entry("spring.flyway.placeholders.app_user", TestcontainersConfiguration.APP_USER),
            Map.entry("ballast.password.memory-kib", "8"),
            Map.entry("ballast.password.iterations", "1"),
            Map.entry("ballast.password.parallelism", "1"),
            Map.entry("ballast.seed.email", email),
            Map.entry("ballast.seed.password", "senha-de-teste-12345"),
            Map.entry("ballast.seed.name", "Seed"));

    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(BallastApplication.class)
            .profiles("seed")
            // Argumentos de linha de comando: mandam mais que o application.yml.
            .run(
                properties.entrySet().stream()
                    .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                    .toArray(String[]::new))) {
      assertThat(context).isNotInstanceOf(WebApplicationContext.class);
      int count =
          context
              .getBean(JdbcClient.class)
              .sql("SELECT count(*) FROM usuario WHERE email = :email")
              .param("email", email)
              .query(Integer.class)
              .single();
      assertThat(count).isEqualTo(1);
    }
  }
}
