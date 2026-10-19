package com.felipe.ballast.health;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class HealthRepository {

  private static final int QUERY_TIMEOUT_SECONDS = 2;

  private final JdbcClient jdbc;

  HealthRepository(DataSource dataSource) {
    // Template próprio: o limite vale só para o health, sem cortar consultas
    // pesadas do resto da API. Estoura como QueryTimeoutException (DataAccessException).
    JdbcTemplate template = new JdbcTemplate(dataSource);
    template.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = JdbcClient.create(template);
  }

  String findMessage() {
    return jdbc.sql("SELECT mensagem FROM health_check WHERE id = 1").query(String.class).single();
  }
}
