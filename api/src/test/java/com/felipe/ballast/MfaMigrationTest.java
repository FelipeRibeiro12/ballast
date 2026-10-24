package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MfaMigrationTest {

  @Autowired JdbcClient jdbc;

  private UUID usuario() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", id)
        .param("email", "mfa-" + id + "@example.com")
        .update();
    return id;
  }

  private JdbcClient.StatementSpec mfa(UUID usuario, int bytes) {
    return jdbc.sql("INSERT INTO mfa (usuario_id, segredo_cifrado) VALUES (:u, :s)")
        .param("u", usuario)
        .param("s", new byte[bytes]);
  }

  private JdbcClient.StatementSpec codigo(UUID usuario, int bytes) {
    return jdbc.sql(
            "INSERT INTO mfa_recuperacao (id, usuario_id, codigo_hash) VALUES (:id, :u, :h)")
        .param("id", UUID.randomUUID())
        .param("u", usuario)
        .param("h", new byte[bytes]);
  }

  private JdbcClient.StatementSpec desafio(UUID usuario) {
    return jdbc.sql(
            "INSERT INTO desafio_mfa (id, usuario_id, token_hash, expira_em)"
                + " VALUES (:id, :u, decode(md5(random()::text) || md5(random()::text), 'hex'), :e)")
        .param("id", UUID.randomUUID())
        .param("u", usuario)
        .param("e", Instant.now().atOffset(ZoneOffset.UTC));
  }

  private void assertConstraint(String constraint, JdbcClient.StatementSpec insert) {
    assertThatThrownBy(insert::update)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }

  private long count(String table, UUID usuario) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE usuario_id = :u")
        .param("u", usuario)
        .query(Long.class)
        .single();
  }

  @Test
  void secretHasTheSizeOfNonceSecretAndTag() {
    assertConstraint("mfa_segredo_cifrado_tamanho", mfa(usuario(), 20));
  }

  @Test
  void recoveryCodeNeedsAnMfaRowAndASha256() {
    UUID semMfa = usuario();
    assertThatThrownBy(() -> codigo(semMfa, 32).update())
        .isInstanceOf(DataIntegrityViolationException.class);

    UUID comMfa = usuario();
    mfa(comMfa, 48).update();
    assertConstraint("mfa_recuperacao_hash_sha256", codigo(comMfa, 16));
  }

  @Test
  void sameRecoveryCodeTwiceForTheSameUserIsRefused() {
    UUID usuario = usuario();
    mfa(usuario, 48).update();
    codigo(usuario, 32).update();

    assertConstraint("mfa_recuperacao_codigo_unico", codigo(usuario, 32));
  }

  @Test
  void turningMfaOffTakesTheRecoveryCodesAlong() {
    UUID usuario = usuario();
    mfa(usuario, 48).update();
    codigo(usuario, 32).update();

    jdbc.sql("DELETE FROM mfa WHERE usuario_id = :u").param("u", usuario).update();

    assertThat(count("mfa_recuperacao", usuario)).isZero();
  }

  @Test
  void deletingTheUserTakesEverythingAlong() {
    UUID usuario = usuario();
    mfa(usuario, 48).update();
    codigo(usuario, 32).update();
    desafio(usuario).update();

    jdbc.sql("DELETE FROM usuario WHERE id = :u").param("u", usuario).update();

    assertThat(count("mfa", usuario)).isZero();
    assertThat(count("mfa_recuperacao", usuario)).isZero();
    assertThat(count("desafio_mfa", usuario)).isZero();
  }
}
