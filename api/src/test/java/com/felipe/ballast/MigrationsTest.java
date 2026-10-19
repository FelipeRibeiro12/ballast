package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MigrationsTest {

  @Autowired JdbcClient jdbc;

  @Test
  void createsTablesOnEmptyDatabase() {
    List<String> tables =
        jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
            .query(String.class)
            .list();

    assertThat(tables).contains("health_check", "usuario", "sessao", "tentativa_login");
  }

  @Test
  void appliesBothMigrationsInOrder() {
    List<String> versions =
        jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
            .query(String.class)
            .list();

    assertThat(versions).containsExactly("1", "2");
  }
}
