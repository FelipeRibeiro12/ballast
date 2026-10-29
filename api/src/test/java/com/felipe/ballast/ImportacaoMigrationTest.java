package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ImportacaoMigrationTest {

  private static final String HASH = "a".repeat(64);

  @Autowired JdbcClient jdbc;

  private UUID usuario() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", id)
        .param("email", "imp-" + id + "@example.com")
        .update();
    return id;
  }

  private UUID conta(UUID usuario) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :u, :nome, 'corrente')")
        .param("id", id)
        .param("u", usuario)
        .param("nome", "C " + id)
        .update();
    return id;
  }

  private UUID categoria(UUID usuario, String tipo) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :u, :nome, :t)")
        .param("id", id)
        .param("u", usuario)
        .param("nome", "K " + id)
        .param("t", tipo)
        .update();
    return id;
  }

  private JdbcClient.StatementSpec insertImportacao(UUID usuario, UUID conta, String hash) {
    return jdbc.sql(
            "INSERT INTO importacao (id, usuario_id, conta_id, hash_arquivo, total_linhas,"
                + " total_invalidas) VALUES (:id, :u, :c, :h, 2, 0)")
        .param("id", UUID.randomUUID())
        .param("u", usuario)
        .param("c", conta)
        .param("h", hash);
  }

  private UUID importacao(UUID usuario, UUID conta) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO importacao (id, usuario_id, conta_id, hash_arquivo, total_linhas,"
                + " total_invalidas) VALUES (:id, :u, :c, :h, 2, 0)")
        .param("id", id)
        .param("u", usuario)
        .param("c", conta)
        .param("h", UUID.randomUUID().toString().replace("-", "").repeat(2))
        .update();
    return id;
  }

  private JdbcClient.StatementSpec insertLinha(
      UUID usuario, UUID importacao, int ordem, String tipo, String valor, String invalida) {
    return jdbc.sql(
            "INSERT INTO importacao_linha (id, usuario_id, importacao_id, ordem, bruta, data, tipo,"
                + " valor, titulo, invalida_motivo, proposta)"
                + " VALUES (:id, :u, :i, :o, CAST('{}' AS jsonb),"
                + " CASE WHEN CAST(:inv AS text) IS NULL THEN DATE '2026-10-03' END,"
                + " CASE WHEN CAST(:inv AS text) IS NULL THEN CAST(:t AS text) END,"
                + " CASE WHEN CAST(:inv AS text) IS NULL THEN CAST(:v AS numeric) END,"
                + " 'Padaria', CAST(:inv AS text), 'ignorar')")
        .param("id", UUID.randomUUID())
        .param("u", usuario)
        .param("i", importacao)
        .param("o", ordem)
        .param("t", tipo)
        .param("v", valor)
        .param("inv", invalida);
  }

  private UUID lancamento(UUID usuario, UUID conta, UUID categoria, String hash, UUID linha) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,"
                + " data_competencia, data_caixa, hash_externo, importacao_linha_id)"
                + " VALUES (:id, :u, 'despesa', 'x', :k, :c, 10.00, DATE '2026-10-03',"
                + " DATE '2026-10-03', :h, :l)")
        .param("id", id)
        .param("u", usuario)
        .param("k", categoria)
        .param("c", conta)
        .param("h", hash)
        .param("l", linha)
        .update();
    return id;
  }

  private void assertConstraint(String constraint, JdbcClient.StatementSpec insert) {
    assertThatThrownBy(insert::update)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }

  @Test
  void theSameFileIsUniquePerUserButFreeForAnotherUser() {
    UUID a = usuario();
    UUID b = usuario();
    insertImportacao(a, conta(a), HASH).update();

    insertImportacao(b, conta(b), HASH).update();

    assertConstraint("importacao_usuario_hash_unico", insertImportacao(a, conta(a), HASH));
  }

  @Test
  void refusesAccountOfAnotherUser() {
    UUID a = usuario();
    UUID b = usuario();

    assertConstraint("importacao_conta_do_usuario", insertImportacao(a, conta(b), HASH));
  }

  @Test
  void checksHashStatusAndTotals() {
    UUID a = usuario();
    UUID conta = conta(a);

    assertConstraint("importacao_hash_formato", insertImportacao(a, conta, "ABC"));
    assertConstraint(
        "importacao_totais_faixa",
        jdbc.sql(
                "INSERT INTO importacao (id, usuario_id, conta_id, hash_arquivo, total_linhas,"
                    + " total_invalidas) VALUES (:id, :u, :c, :h, 2001, 0)")
            .param("id", UUID.randomUUID())
            .param("u", a)
            .param("c", conta)
            .param("h", HASH));
    assertConstraint(
        "importacao_confirmada_com_data",
        jdbc.sql(
                "INSERT INTO importacao (id, usuario_id, conta_id, hash_arquivo, total_linhas,"
                    + " total_invalidas, status) VALUES (:id, :u, :c, :h, 1, 0, 'confirmada')")
            .param("id", UUID.randomUUID())
            .param("u", a)
            .param("c", conta)
            .param("h", HASH));
  }

  @Test
  void checksLineShape() {
    UUID a = usuario();
    UUID importacao = importacao(a, conta(a));
    insertLinha(a, importacao, 1, "despesa", "10.00", null).update();

    assertConstraint(
        "importacao_linha_ordem_unica", insertLinha(a, importacao, 1, "despesa", "10.00", null));
    assertConstraint(
        "importacao_linha_valor_faixa", insertLinha(a, importacao, 2, "despesa", "0.00", null));
    assertConstraint(
        "importacao_linha_tipo_valido", insertLinha(a, importacao, 3, "outro", "10.00", null));
    insertLinha(a, importacao, 4, null, null, "valor inválido").update();
  }

  @Test
  void refusesLineOfAnotherUsersImportacao() {
    UUID a = usuario();
    UUID b = usuario();
    UUID importacaoA = importacao(a, conta(a));

    assertConstraint(
        "importacao_linha_da_importacao_do_usuario",
        insertLinha(b, importacaoA, 1, "despesa", "10.00", null));
  }

  @Test
  void hashExternoIsUniquePerUserAndManyNullsAreFine() {
    UUID a = usuario();
    UUID b = usuario();
    UUID contaA = conta(a);
    UUID categoriaA = categoria(a, "despesa");
    lancamento(a, contaA, categoriaA, null, null);
    lancamento(a, contaA, categoriaA, null, null);
    lancamento(a, contaA, categoriaA, HASH, null);
    lancamento(b, conta(b), categoria(b, "despesa"), HASH, null);

    assertThatThrownBy(() -> lancamento(a, contaA, categoriaA, HASH, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("lancamento_usuario_hash_externo_unico");
    assertThatThrownBy(() -> lancamento(a, contaA, categoriaA, "xyz", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("lancamento_hash_externo_formato");
  }

  @Test
  void deletingTheEntryOrTheImportReleasesTheLinks() {
    UUID a = usuario();
    UUID conta = conta(a);
    UUID categoria = categoria(a, "despesa");
    UUID importacao = importacao(a, conta);
    insertLinha(a, importacao, 1, "despesa", "10.00", null).update();
    UUID linha =
        jdbc.sql("SELECT id FROM importacao_linha WHERE importacao_id = :i")
            .param("i", importacao)
            .query(UUID.class)
            .single();
    UUID criado = lancamento(a, conta, categoria, HASH, linha);
    jdbc.sql("UPDATE importacao_linha SET lancamento_id = :l WHERE id = :id")
        .param("l", criado)
        .param("id", linha)
        .update();

    jdbc.sql("DELETE FROM lancamento WHERE id = :id").param("id", criado).update();

    assertThat(
            jdbc.sql("SELECT lancamento_id FROM importacao_linha WHERE id = :id")
                .param("id", linha)
                .query(UUID.class)
                .optional())
        .isEmpty();

    UUID outro = lancamento(a, conta, categoria, "b".repeat(64), linha);
    jdbc.sql("DELETE FROM importacao WHERE id = :id").param("id", importacao).update();

    assertThat(
            jdbc.sql("SELECT count(*) FROM importacao_linha WHERE importacao_id = :i")
                .param("i", importacao)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("SELECT importacao_linha_id FROM lancamento WHERE id = :id")
                .param("id", outro)
                .query(UUID.class)
                .optional())
        .isEmpty();
  }

  @Test
  void deletingTheUserDeletesImportsAndLines() {
    UUID a = usuario();
    UUID importacao = importacao(a, conta(a));
    insertLinha(a, importacao, 1, "despesa", "10.00", null).update();

    jdbc.sql("DELETE FROM usuario WHERE id = :id").param("id", a).update();

    assertThat(
            jdbc.sql("SELECT count(*) FROM importacao_linha WHERE importacao_id = :i")
                .param("i", importacao)
                .query(Long.class)
                .single())
        .isZero();
  }
}
