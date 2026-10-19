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
  void allowsDmlOnConta() {
    UUID usuarioId = UUID.randomUUID();
    UUID contaId = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuarioId)
        .param("email", "conta-dml-" + usuarioId + "@example.com")
        .update();

    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuarioId, 'Caixa', 'dinheiro')")
        .param("id", contaId)
        .param("usuarioId", usuarioId)
        .update();
    jdbc.sql("UPDATE conta SET arquivada = true WHERE id = :id").param("id", contaId).update();
    boolean arquivada =
        jdbc.sql("SELECT arquivada FROM conta WHERE id = :id")
            .param("id", contaId)
            .query(Boolean.class)
            .single();
    int deleted = jdbc.sql("DELETE FROM conta WHERE id = :id").param("id", contaId).update();

    assertThat(arquivada).isTrue();
    assertThat(deleted).isEqualTo(1);
  }

  @Test
  void deniesDdlAndTruncate() {
    assertDenied("DROP TABLE usuario");
    assertDenied("CREATE TABLE intruso (id int)");
    assertDenied("TRUNCATE usuario");
    assertDenied("TRUNCATE conta");
    assertDenied("TRUNCATE categoria");
    assertDenied("TRUNCATE lancamento");
    assertDenied("TRUNCATE transferencia");
  }

  @Test
  void allowsDmlOnCategoriaAndLancamento() {
    UUID usuarioId = UUID.randomUUID();
    UUID contaId = UUID.randomUUID();
    UUID categoriaId = UUID.randomUUID();
    UUID lancamentoId = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuarioId)
        .param("email", "lanc-dml-" + usuarioId + "@example.com")
        .update();
    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuarioId, 'Caixa', 'dinheiro')")
        .param("id", contaId)
        .param("usuarioId", usuarioId)
        .update();
    // nome fora da lista das sementes, que já nasceram junto com o usuário
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :usuarioId, 'Pets', 'despesa')")
        .param("id", categoriaId)
        .param("usuarioId", usuarioId)
        .update();
    jdbc.sql(
            "INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,"
                + " data_competencia, data_caixa) VALUES (:id, :usuarioId, 'despesa', 'Ração',"
                + " :categoriaId, :contaId, 10.50, DATE '2026-10-19', DATE '2026-10-19')")
        .param("id", lancamentoId)
        .param("usuarioId", usuarioId)
        .param("categoriaId", categoriaId)
        .param("contaId", contaId)
        .update();
    jdbc.sql("UPDATE lancamento SET titulo = 'Ração premium' WHERE id = :id")
        .param("id", lancamentoId)
        .update();
    jdbc.sql("UPDATE categoria SET cor = '#FFAA00' WHERE id = :id")
        .param("id", categoriaId)
        .update();
    String titulo =
        jdbc.sql("SELECT titulo FROM lancamento WHERE id = :id")
            .param("id", lancamentoId)
            .query(String.class)
            .single();
    int deleted =
        jdbc.sql("DELETE FROM lancamento WHERE id = :id").param("id", lancamentoId).update();
    int deletedCategoria =
        jdbc.sql("DELETE FROM categoria WHERE id = :id").param("id", categoriaId).update();

    assertThat(titulo).isEqualTo("Ração premium");
    assertThat(deleted).isEqualTo(1);
    assertThat(deletedCategoria).isEqualTo(1);
  }

  @Test
  void allowsDmlOnTransferencia() {
    UUID usuarioId = UUID.randomUUID();
    UUID contaA = UUID.randomUUID();
    UUID contaB = UUID.randomUUID();
    UUID saida = UUID.randomUUID();
    UUID entrada = UUID.randomUUID();
    UUID transferenciaId = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuarioId)
        .param("email", "transf-dml-" + usuarioId + "@example.com")
        .update();
    for (UUID[] conta : new UUID[][] {{contaA}, {contaB}}) {
      jdbc.sql(
              "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuarioId, :nome,"
                  + " 'dinheiro')")
          .param("id", conta[0])
          .param("usuarioId", usuarioId)
          .param("nome", "Conta " + conta[0])
          .update();
    }
    insertPerna(usuarioId, saida, contaA);
    insertPerna(usuarioId, entrada, contaB);

    jdbc.sql(
            "INSERT INTO transferencia (id, usuario_id, saida_id, entrada_id, conta_saida_id,"
                + " conta_entrada_id) VALUES (:id, :usuarioId, :saida, :entrada, :contaA, :contaB)")
        .param("id", transferenciaId)
        .param("usuarioId", usuarioId)
        .param("saida", saida)
        .param("entrada", entrada)
        .param("contaA", contaA)
        .param("contaB", contaB)
        .update();
    UUID lida =
        jdbc.sql("SELECT saida_id FROM transferencia WHERE id = :id")
            .param("id", transferenciaId)
            .query(UUID.class)
            .single();
    int deleted =
        jdbc.sql("DELETE FROM transferencia WHERE id = :id").param("id", transferenciaId).update();
    jdbc.sql("DELETE FROM lancamento WHERE usuario_id = :id").param("id", usuarioId).update();

    assertThat(lida).isEqualTo(saida);
    assertThat(deleted).isEqualTo(1);
  }

  private void insertPerna(UUID usuarioId, UUID id, UUID contaId) {
    jdbc.sql(
            "INSERT INTO lancamento (id, usuario_id, tipo, titulo, conta_id, valor,"
                + " data_competencia, data_caixa) VALUES (:id, :usuarioId, 'transferencia',"
                + " 'Transferência', :contaId, 10.00, DATE '2026-10-19', DATE '2026-10-19')")
        .param("id", id)
        .param("usuarioId", usuarioId)
        .param("contaId", contaId)
        .update();
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
