package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.felipe.ballast.BallastApplication;
import com.felipe.ballast.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Container próprio e vazio: o que se prova é que a recusa vem antes de qualquer migration.
@Testcontainers
class InsecureCookieBeforeDatabaseTest {

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  @Test
  void refusesToStartBeforeFlywayTouchesTheDatabase() throws Exception {
    assertThatThrownBy(
            () ->
                new SpringApplicationBuilder(BallastApplication.class)
                    .run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + TestcontainersConfiguration.APP_USER,
                        "--spring.datasource.password=" + TestcontainersConfiguration.APP_PASSWORD,
                        "--spring.flyway.user=" + postgres.getUsername(),
                        "--spring.flyway.password=" + postgres.getPassword(),
                        "--spring.flyway.placeholders.app_user="
                            + TestcontainersConfiguration.APP_USER,
                        "--server.port=0",
                        "--ballast.app-env=staging",
                        "--ballast.cookie-secure=false"))
        .hasStackTraceContaining("COOKIE_SECURE=false só é aceito com APP_ENV=development");

    try (Connection connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement statement = connection.createStatement();
        ResultSet tables =
            statement.executeQuery(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")) {
      tables.next();
      assertThat(tables.getInt(1)).as("tabelas no schema public").isZero();
    }
  }
}
