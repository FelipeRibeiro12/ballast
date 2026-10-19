package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AppRolePrivilegesTest {

  @Autowired JdbcClient jdbc;

  @Test
  void connectsAsTheApplicationRole() {
    String user = jdbc.sql("SELECT current_user").query(String.class).single();

    assertThat(user).isEqualTo(TestcontainersConfiguration.APP_USER);
  }

  @Test
  void allowsDmlOnUsuario() {
    UUID id = UUID.randomUUID();
    String email = "dml-" + id + "@example.com";

    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", id)
        .param("email", email)
        .update();
    jdbc.sql("UPDATE usuario SET nome = 'Teste' WHERE id = :id").param("id", id).update();
    String nome =
        jdbc.sql("SELECT nome FROM usuario WHERE id = :id")
            .param("id", id)
            .query(String.class)
            .single();
    int deleted = jdbc.sql("DELETE FROM usuario WHERE id = :id").param("id", id).update();

    assertThat(nome).isEqualTo("Teste");
    assertThat(deleted).isEqualTo(1);
  }

  @Test
  void deniesDdlAndTruncate() {
    assertDenied("DROP TABLE usuario");
    assertDenied("CREATE TABLE intruso (id int)");
    assertDenied("TRUNCATE usuario");
  }

  @Test
  void deniesAccessToFlywayHistory() {
    assertDenied("SELECT version FROM flyway_schema_history");
    assertDenied("UPDATE flyway_schema_history SET success = true WHERE version = '1'");
  }

  // DROP falha com "must be owner" e os demais com "permission denied"; os dois são negação
  private void assertDenied(String sql) {
    assertThatThrownBy(() -> jdbc.sql(sql).update())
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageMatching("(?s).*(permission denied|must be owner).*");
  }
}
