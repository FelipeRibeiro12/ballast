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
    assertDenied("TRUNCATE regra_recorrencia");
    assertDenied("TRUNCATE template_lancamento");
    assertDenied("TRUNCATE template_divisao");
    assertDenied("TRUNCATE cartao");
    assertDenied("TRUNCATE conta_categoria_permitida");
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
  void allowsDmlOnContaCategoriaPermitida() {
    UUID usuarioId = UUID.randomUUID();
    UUID contaId = UUID.randomUUID();
    UUID categoriaId = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuarioId)
        .param("email", "permitida-dml-" + usuarioId + "@example.com")
        .update();
    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuarioId, 'VR',"
                + " 'beneficio')")
        .param("id", contaId)
        .param("usuarioId", usuarioId)
        .update();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :usuarioId, 'Pets',"
                + " 'despesa')")
        .param("id", categoriaId)
        .param("usuarioId", usuarioId)
        .update();

    int inserted =
        jdbc.sql(
                "INSERT INTO conta_categoria_permitida (conta_id, categoria_id, usuario_id)"
                    + " VALUES (:conta, :categoria, :usuario)")
            .param("conta", contaId)
            .param("categoria", categoriaId)
            .param("usuario", usuarioId)
            .update();
    long found =
        jdbc.sql("SELECT count(*) FROM conta_categoria_permitida WHERE conta_id = :conta")
            .param("conta", contaId)
            .query(Long.class)
            .single();
    int deleted =
        jdbc.sql("DELETE FROM conta_categoria_permitida WHERE conta_id = :conta")
            .param("conta", contaId)
            .update();

    assertThat(inserted).isEqualTo(1);
    assertThat(found).isEqualTo(1);
    assertThat(deleted).isEqualTo(1);
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

  @Test
  void allowsDmlOnRegraAndTemplate() {
    UUID usuarioId = UUID.randomUUID();
    UUID contaId = UUID.randomUUID();
    UUID categoriaId = UUID.randomUUID();
    UUID regraId = UUID.randomUUID();
    UUID templateId = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuarioId)
        .param("email", "rec-dml-" + usuarioId + "@example.com")
        .update();
    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuarioId, 'Caixa', 'dinheiro')")
        .param("id", contaId)
        .param("usuarioId", usuarioId)
        .update();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :usuarioId, 'Pets', 'despesa')")
        .param("id", categoriaId)
        .param("usuarioId", usuarioId)
        .update();

    jdbc.sql(
            "INSERT INTO regra_recorrencia (id, usuario_id, frequencia, dia_do_mes, inicio)"
                + " VALUES (:id, :usuarioId, 'mensal', 5, DATE '2026-10-19')")
        .param("id", regraId)
        .param("usuarioId", usuarioId)
        .update();
    jdbc.sql(
            "INSERT INTO template_lancamento (id, usuario_id, tipo, titulo, categoria_id,"
                + " conta_id, valor_total, regra_id) VALUES (:id, :usuarioId, 'despesa', 'Ração',"
                + " :categoriaId, :contaId, 10.00, :regraId)")
        .param("id", templateId)
        .param("usuarioId", usuarioId)
        .param("categoriaId", categoriaId)
        .param("contaId", contaId)
        .param("regraId", regraId)
        .update();
    jdbc.sql("UPDATE template_lancamento SET ativo = false WHERE id = :id")
        .param("id", templateId)
        .update();
    boolean ativo =
        jdbc.sql("SELECT ativo FROM template_lancamento WHERE id = :id")
            .param("id", templateId)
            .query(Boolean.class)
            .single();
    int deletedTemplate =
        jdbc.sql("DELETE FROM template_lancamento WHERE id = :id").param("id", templateId).update();
    int deletedRegra =
        jdbc.sql("DELETE FROM regra_recorrencia WHERE id = :id").param("id", regraId).update();

    assertThat(ativo).isFalse();
    assertThat(deletedTemplate).isEqualTo(1);
    assertThat(deletedRegra).isEqualTo(1);
  }

  @Test
  void allowsDmlOnTemplateDivisao() {
    UUID usuarioId = UUID.randomUUID();
    UUID contaId = UUID.randomUUID();
    UUID categoriaId = UUID.randomUUID();
    UUID regraId = UUID.randomUUID();
    UUID templateId = UUID.randomUUID();
    UUID divisaoId = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuarioId)
        .param("email", "div-dml-" + usuarioId + "@example.com")
        .update();
    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuarioId, 'Caixa', 'dinheiro')")
        .param("id", contaId)
        .param("usuarioId", usuarioId)
        .update();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :usuarioId, 'Pets', 'despesa')")
        .param("id", categoriaId)
        .param("usuarioId", usuarioId)
        .update();
    jdbc.sql(
            "INSERT INTO regra_recorrencia (id, usuario_id, frequencia, dia_do_mes, inicio)"
                + " VALUES (:id, :usuarioId, 'mensal', 5, DATE '2026-10-19')")
        .param("id", regraId)
        .param("usuarioId", usuarioId)
        .update();
    jdbc.sql(
            "INSERT INTO template_lancamento (id, usuario_id, tipo, titulo, categoria_id,"
                + " conta_id, valor_total, regra_id) VALUES (:id, :usuarioId, 'despesa', 'Ração',"
                + " :categoriaId, :contaId, 10.00, :regraId)")
        .param("id", templateId)
        .param("usuarioId", usuarioId)
        .param("categoriaId", categoriaId)
        .param("contaId", contaId)
        .param("regraId", regraId)
        .update();

    jdbc.sql(
            "INSERT INTO template_divisao (id, usuario_id, template_id, dia_do_mes, percentual,"
                + " ordem) VALUES (:id, :usuarioId, :templateId, 5, 40.00, 1)")
        .param("id", divisaoId)
        .param("usuarioId", usuarioId)
        .param("templateId", templateId)
        .update();
    jdbc.sql("UPDATE template_divisao SET percentual = 50.00 WHERE id = :id")
        .param("id", divisaoId)
        .update();
    String percentual =
        jdbc.sql("SELECT percentual::text FROM template_divisao WHERE id = :id")
            .param("id", divisaoId)
            .query(String.class)
            .single();
    int deleted =
        jdbc.sql("DELETE FROM template_divisao WHERE id = :id").param("id", divisaoId).update();

    assertThat(percentual).isEqualTo("50.00");
    assertThat(deleted).isEqualTo(1);
  }

  @Test
  void allowsDmlOnCartao() {
    UUID usuarioId = UUID.randomUUID();
    UUID cartaoId = UUID.randomUUID();
    UUID pagamentoId = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuarioId)
        .param("email", "cartao-dml-" + usuarioId + "@example.com")
        .update();
    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuarioId, 'Banco',"
                + " 'corrente'), (:cartaoId, :usuarioId, 'Cartão', 'cartao_credito')")
        .param("id", pagamentoId)
        .param("cartaoId", cartaoId)
        .param("usuarioId", usuarioId)
        .update();

    jdbc.sql(
            "INSERT INTO cartao (conta_id, usuario_id, limite, dia_fechamento, dia_vencimento,"
                + " conta_pagamento_id) VALUES (:cartaoId, :usuarioId, 5000.00, 25, 5,"
                + " :pagamentoId)")
        .param("cartaoId", cartaoId)
        .param("usuarioId", usuarioId)
        .param("pagamentoId", pagamentoId)
        .update();
    jdbc.sql("UPDATE cartao SET limite = 7000.00 WHERE conta_id = :id")
        .param("id", cartaoId)
        .update();
    String limite =
        jdbc.sql("SELECT limite::text FROM cartao WHERE conta_id = :id")
            .param("id", cartaoId)
            .query(String.class)
            .single();
    int deleted =
        jdbc.sql("DELETE FROM cartao WHERE conta_id = :id").param("id", cartaoId).update();

    assertThat(limite).isEqualTo("7000.00");
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
