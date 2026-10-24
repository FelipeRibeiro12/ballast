package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// O deploy de produção em dois passos (ADR-044): o perfil migracao roda o Flyway como dono do
// schema e sai; o perfil producao sobe sem Flyway e sem a credencial de dono.
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class DeployProfilesTest {

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  // As variáveis de ambiente que cada passo recebe, como argumento: mandam mais que o yml.
  private static String[] args(Map<String, String> variables) {
    List<String> args = new ArrayList<>();
    variables.forEach((key, value) -> args.add("--" + key + "=" + value));
    // Os jobs falam com o Banco Central e a CoinGecko; aqui não interessam.
    args.add("--ballast.recurrence.job-enabled=false");
    args.add("--ballast.indices.job-enabled=false");
    args.add("--ballast.cotacoes.job-enabled=false");
    args.add("--ballast.password.memory-kib=8");
    args.add("--ballast.password.iterations=1");
    args.add("--ballast.password.parallelism=1");
    return args.toArray(String[]::new);
  }

  private static Properties yaml(String file) {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(new ClassPathResource(file));
    return factory.getObject();
  }

  // Com o agendador ligado a JVM do passo de migração não sai. Lido direto dos arquivos porque o
  // application.properties dos testes desliga os jobs e esconderia a chave que faltar.
  @Test
  void migrationProfileTurnsOffEveryJob() {
    Properties defaults = yaml("application.yml");
    Properties migration = yaml("application-migracao.yml");
    List<String> jobs =
        defaults.keySet().stream()
            .map(String::valueOf)
            .filter(key -> key.endsWith(".job-enabled"))
            .toList();

    assertThat(jobs).contains("ballast.identity-cleanup.job-enabled");
    for (String job : jobs) {
      // O YAML devolve booleano, não texto.
      assertThat(migration.get(job)).as(job).isEqualTo(false);
    }
  }

  @Test
  void migratesAsOwnerThenRunsWithoutFlywayNorTheOwnerCredential(CapturedOutput output)
      throws Exception {
    try (ConfigurableApplicationContext migration =
        new SpringApplicationBuilder(BallastApplication.class)
            .profiles("migracao")
            .run(
                args(
                    Map.of(
                        "DATABASE_URL", postgres.getJdbcUrl(),
                        "DB_MIGRATION_USER", postgres.getUsername(),
                        "DB_MIGRATION_PASSWORD", postgres.getPassword(),
                        // Só o nome, para o GRANT da V3: a senha da aplicação não vai ao passo.
                        "DB_USER", TestcontainersConfiguration.APP_USER)))) {
      assertThat(migration).isNotInstanceOf(WebApplicationContext.class);
      assertThat(
              migration
                  .getBean(JdbcClient.class)
                  .sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                  .query(Integer.class)
                  .single())
          .isGreaterThanOrEqualTo(18);
    }

    try (ConfigurableApplicationContext app =
        new SpringApplicationBuilder(BallastApplication.class)
            .profiles("producao")
            .run(
                args(
                    Map.of(
                        "DATABASE_URL",
                        postgres.getJdbcUrl(),
                        "DB_USER",
                        TestcontainersConfiguration.APP_USER,
                        "DB_PASSWORD",
                        TestcontainersConfiguration.APP_PASSWORD,
                        "APP_ENV",
                        "production",
                        "server.port",
                        "0",
                        "ballast.rate-limit.login-per-minute",
                        "1")))) {
      assertThat(app.getBeanNamesForType(Flyway.class)).isEmpty();
      int port = ((WebServerApplicationContext) app).getWebServer().getPort();
      HttpClient http = HttpClient.newHttpClient();

      HttpResponse<String> health =
          http.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(health.statusCode()).isEqualTo(200);

      // Atrás do Caddy o par da conexão é o proxy; o log de segurança tem de levar o visitante.
      http.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/auth/login"))
              .header("Content-Type", "application/json")
              .header("X-Forwarded-For", "203.0.113.9")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "{\"email\":\"ninguem@exemplo.test\",\"senha\":\"senha-errada-12345\"}"))
              .build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(output.getAll()).contains("ip=203.0.113.9");

      // O limite por IP conta o visitante, não o Caddy (ADR-049): o segundo login do mesmo
      // visitante passa do limite de 1, e o de outro visitante não.
      assertThat(login(http, port, "203.0.113.9").statusCode()).isEqualTo(429);
      assertThat(login(http, port, "203.0.113.10").statusCode()).isEqualTo(401);
    }
  }

  private static HttpResponse<String> login(HttpClient http, int port, String visitor)
      throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/auth/login"))
            .header("Content-Type", "application/json")
            .header("X-Forwarded-For", visitor)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"email\":\"ninguem@exemplo.test\",\"senha\":\"senha-errada-12345\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
