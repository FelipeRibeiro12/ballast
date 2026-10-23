package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MetaMigrationTest {

  @Autowired JdbcClient jdbc;

  private UUID usuario() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", id)
        .param("email", "meta-" + id + "@example.com")
        .update();
    return id;
  }

  private JdbcClient.StatementSpec meta(UUID id, UUID usuario, String titulo, String alvo) {
    return jdbc.sql(
            "INSERT INTO meta (id, usuario_id, titulo, valor_alvo)"
                + " VALUES (:id, :u, :titulo, CAST(:alvo AS numeric))")
        .param("id", id)
        .param("u", usuario)
        .param("titulo", titulo)
        .param("alvo", alvo);
  }

  private UUID metaOk(UUID usuario) {
    UUID id = UUID.randomUUID();
    meta(id, usuario, "Viagem", "1000").update();
    return id;
  }

  private JdbcClient.StatementSpec aporte(UUID usuario, UUID meta, String valor, String data) {
    return jdbc.sql(
            "INSERT INTO aporte_meta (id, usuario_id, meta_id, valor, data)"
                + " VALUES (:id, :u, :m, CAST(:valor AS numeric), :data)")
        .param("id", UUID.randomUUID())
        .param("u", usuario)
        .param("m", meta)
        .param("valor", valor)
        .param("data", LocalDate.parse(data));
  }

  private void assertConstraint(String constraint, JdbcClient.StatementSpec insert) {
    assertThatThrownBy(insert::update)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }

  @Test
  void rejectsBlankOrPaddedTitleAndTargetOutOfRange() {
    UUID usuario = usuario();

    assertConstraint("meta_titulo_tamanho", meta(UUID.randomUUID(), usuario, "", "1"));
    assertConstraint("meta_titulo_tamanho", meta(UUID.randomUUID(), usuario, " Viagem", "1"));
    assertConstraint("meta_valor_alvo_faixa", meta(UUID.randomUUID(), usuario, "Viagem", "0"));
    assertConstraint(
        "meta_valor_alvo_faixa", meta(UUID.randomUUID(), usuario, "Viagem", "90071992547409.92"));
  }

  @Test
  void acceptsDepositAndWithdrawalButNotZero() {
    UUID usuario = usuario();
    UUID meta = metaOk(usuario);

    assertThat(aporte(usuario, meta, "150.00", "2026-10-01").update()).isEqualTo(1);
    assertThat(aporte(usuario, meta, "-50.00", "2026-10-02").update()).isEqualTo(1);
    assertConstraint("aporte_meta_valor_faixa", aporte(usuario, meta, "0", "2026-10-03"));
  }

  @Test
  void rejectsADepositIntoAGoalOfAnotherUser() {
    UUID ana = usuario();
    UUID bia = usuario();
    UUID metaDaBia = metaOk(bia);

    assertConstraint("aporte_meta_meta_do_usuario", aporte(ana, metaDaBia, "10", "2026-10-01"));
  }

  @Test
  void deletingTheGoalTakesItsDeposits() {
    UUID usuario = usuario();
    UUID meta = metaOk(usuario);
    aporte(usuario, meta, "10", "2026-10-01").update();

    jdbc.sql("DELETE FROM meta WHERE id = :id").param("id", meta).update();

    assertThat(
            jdbc.sql("SELECT count(*) FROM aporte_meta WHERE meta_id = :id")
                .param("id", meta)
                .query(Long.class)
                .single())
        .isZero();
  }
}
