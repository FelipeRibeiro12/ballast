package com.felipe.ballast.health;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class HealthRepository {

  private final JdbcClient jdbc;

  HealthRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  String findMessage() {
    return jdbc.sql("SELECT mensagem FROM health_check WHERE id = 1").query(String.class).single();
  }
}
