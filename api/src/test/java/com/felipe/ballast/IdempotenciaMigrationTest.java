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
class IdempotenciaMigrationTest {

  private static final String HASH = "a".repeat(64);

  @Autowired JdbcClient jdbc;

  private UUID usuario() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", id)
        .param("email", "idem-" + id + "@example.com")
        .update();
    return id;
  }

  private JdbcClient.StatementSpec insert(
      UUID usuario, String chave, String hash, Integer status, String corpo) {
    return jdbc.sql(
            "INSERT INTO idempotencia (usuario_id, chave, rota, corpo_hash, status, corpo_resposta)"
                + " VALUES (:u, :k, '/lancamentos', :h, :s, :c)")
        .param("u", usuario)
        .param("k", chave)
        .param("h", hash)
        .param("s", status)
        .param("c", corpo);
  }

  private void assertConstraint(String constraint, JdbcClient.StatementSpec insert) {
    assertThatThrownBy(insert::update)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }

  @Test
  void theSameKeyIsUniquePerUserButFreeForAnotherUser() {
    UUID a = usuario();
    UUID b = usuario();
    insert(a, "k1", HASH, null, null).update();

    insert(b, "k1", HASH, null, null).update();

    assertThatThrownBy(() -> insert(a, "k1", HASH, null, null).update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("idempotencia_pkey");
  }

  @Test
  void checksTheShapeOfKeyHashAndResponse() {
    UUID u = usuario();

    assertConstraint("idempotencia_chave_formato", insert(u, "com espaço", HASH, null, null));
    assertThatThrownBy(() -> insert(u, "a".repeat(81), HASH, null, null).update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertConstraint("idempotencia_hash_formato", insert(u, "k", "ABC", null, null));
    assertConstraint("idempotencia_status_faixa", insert(u, "k", HASH, 400, "{}"));
    assertConstraint("idempotencia_resposta_completa", insert(u, "k", HASH, 201, null));
    assertConstraint("idempotencia_resposta_completa", insert(u, "k", HASH, null, "{}"));
  }

  @Test
  void deletingTheUserDeletesItsKeys() {
    UUID u = usuario();
    insert(u, "k", HASH, 201, "{}").update();

    jdbc.sql("DELETE FROM usuario WHERE id = :id").param("id", u).update();

    assertThat(
            jdbc.sql("SELECT count(*) FROM idempotencia WHERE usuario_id = :u")
                .param("u", u)
                .query(Long.class)
                .single())
        .isZero();
  }
}
