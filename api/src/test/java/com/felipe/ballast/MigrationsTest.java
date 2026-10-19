package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MigrationsTest {

  @Autowired JdbcClient jdbc;
  @Autowired PostgreSQLContainer postgres;

  @Test
  void createsTablesOnEmptyDatabase() {
    List<String> tables =
        jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
            .query(String.class)
            .list();

    assertThat(tables).contains("health_check", "usuario", "sessao", "tentativa_login");
  }

  @Test
  void appliesAllMigrationsInOrder() {
    // o papel da aplicação não lê o histórico (V4), então a consulta vai como dono
    JdbcClient owner =
        JdbcClient.create(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    List<String> versions =
        owner
            .sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
            .query(String.class)
            .list();

    assertThat(versions).containsExactly("1", "2", "3", "4");
  }
}
