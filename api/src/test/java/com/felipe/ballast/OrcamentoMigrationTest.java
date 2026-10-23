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
class OrcamentoMigrationTest {

  @Autowired JdbcClient jdbc;

  private UUID usuario() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", id)
        .param("email", "orcamento-" + id + "@example.com")
        .update();
    return id;
  }

  private UUID categoria(UUID usuario, String tipo) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :u, :nome, :tipo)")
        .param("id", id)
        .param("u", usuario)
        .param("nome", "Categoria " + id)
        .param("tipo", tipo)
        .update();
    return id;
  }

  private JdbcClient.StatementSpec orcamento(
      UUID usuario, UUID categoria, String desde, String teto) {
    return jdbc.sql(
            "INSERT INTO orcamento (usuario_id, categoria_id, desde, teto)"
                + " VALUES (:u, :c, :desde, CAST(:teto AS numeric))")
        .param("u", usuario)
        .param("c", categoria)
        .param("desde", LocalDate.parse(desde))
        .param("teto", teto);
  }

  private void assertConstraint(String constraint, JdbcClient.StatementSpec insert) {
    assertThatThrownBy(insert::update)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }

  @Test
  void acceptsAnExpenseCeilingAndANullOneThatEndsIt() {
    UUID usuario = usuario();
    UUID mercado = categoria(usuario, "despesa");

    assertThat(orcamento(usuario, mercado, "2026-10-01", "800.00").update()).isEqualTo(1);
    assertThat(orcamento(usuario, mercado, "2026-12-01", null).update()).isEqualTo(1);
  }

  @Test
  void rejectsIncomeCategoryAndCategoryOfAnotherUser() {
    UUID ana = usuario();
    UUID bia = usuario();

    assertConstraint(
        "orcamento_categoria_despesa_do_usuario",
        orcamento(ana, categoria(ana, "receita"), "2026-10-01", "1"));
    assertConstraint(
        "orcamento_categoria_despesa_do_usuario",
        orcamento(ana, categoria(bia, "despesa"), "2026-10-01", "1"));
  }

  @Test
  void rejectsAMonthThatDoesNotStartOnDay1OrFallsOutOfRange() {
    UUID usuario = usuario();
    UUID mercado = categoria(usuario, "despesa");

    assertConstraint("orcamento_desde_dia_1", orcamento(usuario, mercado, "2026-10-02", "1"));
    assertConstraint("orcamento_desde_faixa", orcamento(usuario, mercado, "1899-12-01", "1"));
    assertConstraint("orcamento_desde_faixa", orcamento(usuario, mercado, "2200-01-01", "1"));
  }

  @Test
  void rejectsCeilingZeroAndAboveTheSafeInteger() {
    UUID usuario = usuario();
    UUID mercado = categoria(usuario, "despesa");

    assertConstraint("orcamento_teto_faixa", orcamento(usuario, mercado, "2026-10-01", "0"));
    assertConstraint(
        "orcamento_teto_faixa", orcamento(usuario, mercado, "2026-11-01", "90071992547409.92"));
    assertThat(orcamento(usuario, mercado, "2026-12-01", "90071992547409.91").update())
        .isEqualTo(1);
  }

  @Test
  void deletingTheCategoryTakesItsCeilings() {
    UUID usuario = usuario();
    UUID mercado = categoria(usuario, "despesa");
    orcamento(usuario, mercado, "2026-10-01", "800").update();

    jdbc.sql("DELETE FROM categoria WHERE id = :id").param("id", mercado).update();

    assertThat(
            jdbc.sql("SELECT count(*) FROM orcamento WHERE categoria_id = :id")
                .param("id", mercado)
                .query(Long.class)
                .single())
        .isZero();
  }
}
