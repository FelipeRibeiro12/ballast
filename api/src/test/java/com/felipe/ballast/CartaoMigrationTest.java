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
class CartaoMigrationTest {

  @Autowired JdbcClient jdbc;

  @Test
  void acceptsACartaoPaidByCorrenteOrPoupanca() {
    Fixture f = fixture();
    UUID poupanca = conta(f.usuario, "poupanca");

    assertThat(cartao(f, f.cartao, f.pagamento, "5000.00", 25, 5).update()).isEqualTo(1);
    assertThat(cartao(f, conta(f.usuario, "cartao_credito"), poupanca, "0.01", 1, 31).update())
        .isEqualTo(1);
    assertThat(
            cartao(f, conta(f.usuario, "cartao_credito"), f.pagamento, "90071992547409.91", 31, 1)
                .update())
        .isEqualTo(1);
  }

  @Test
  void rejectsCartaoOfAContaOfAnotherUsuario() {
    Fixture f = fixture();
    Fixture other = fixture();

    assertConstraint(
        "cartao_conta_mesmo_usuario_e_tipo",
        cartao(f, other.cartao, f.pagamento, "5000.00", 25, 5));
  }

  @Test
  void rejectsContaPagamentoOfAnotherUsuario() {
    Fixture f = fixture();
    Fixture other = fixture();

    assertConstraint(
        "cartao_pagamento_mesmo_usuario_e_tipo",
        cartao(f, f.cartao, other.pagamento, "5000.00", 25, 5));
  }

  @Test
  void rejectsContaPagamentoEqualToTheCartaoConta() {
    Fixture f = fixture();

    assertConstraint(
        "cartao_pagamento_em_outra_conta", cartao(f, f.cartao, f.cartao, "5000.00", 25, 5));
  }

  @Test
  void rejectsCartaoForAContaThatIsNotCartaoCredito() {
    Fixture f = fixture();

    assertConstraint(
        "cartao_conta_mesmo_usuario_e_tipo",
        cartao(f, conta(f.usuario, "corrente"), f.pagamento, "5000.00", 25, 5));
    assertConstraint(
        "cartao_conta_mesmo_usuario_e_tipo",
        cartao(f, conta(f.usuario, "dinheiro"), f.pagamento, "5000.00", 25, 5));
  }

  @Test
  void rejectsContaPagamentoThatIsNotCorrenteOrPoupanca() {
    Fixture f = fixture();

    assertConstraint(
        "cartao_pagamento_mesmo_usuario_e_tipo",
        cartao(f, f.cartao, conta(f.usuario, "dinheiro"), "5000.00", 25, 5));
    assertConstraint(
        "cartao_pagamento_mesmo_usuario_e_tipo",
        cartao(f, f.cartao, conta(f.usuario, "cartao_credito"), "5000.00", 25, 5));
  }

  @Test
  void rejectsChangingTheTipoOfAContaThatPaysACartao() {
    Fixture f = fixture();
    cartao(f, f.cartao, f.pagamento, "5000.00", 25, 5).update();

    assertConstraint(
        "cartao_pagamento_mesmo_usuario_e_tipo",
        jdbc.sql("UPDATE conta SET tipo = 'dinheiro' WHERE id = :id").param("id", f.pagamento));
  }

  @Test
  void rejectsDiaOutsideOneToThirtyOne() {
    Fixture f = fixture();

    assertConstraint(
        "cartao_dia_fechamento_faixa", cartao(f, f.cartao, f.pagamento, "5000.00", 0, 5));
    assertConstraint(
        "cartao_dia_fechamento_faixa", cartao(f, f.cartao, f.pagamento, "5000.00", 32, 5));
    assertConstraint(
        "cartao_dia_vencimento_faixa", cartao(f, f.cartao, f.pagamento, "5000.00", 25, 0));
    assertConstraint(
        "cartao_dia_vencimento_faixa", cartao(f, f.cartao, f.pagamento, "5000.00", 25, 32));
  }

  @Test
  void rejectsLimiteZeroNegativeOrAboveTheCeiling() {
    Fixture f = fixture();

    for (String limite : new String[] {"0", "0.00", "-1.00", "90071992547409.92"}) {
      assertConstraint("cartao_limite_faixa", cartao(f, f.cartao, f.pagamento, limite, 25, 5));
    }
  }

  @Test
  void rejectsASecondCartaoForTheSameConta() {
    Fixture f = fixture();
    cartao(f, f.cartao, f.pagamento, "5000.00", 25, 5).update();

    assertConstraint("cartao_pkey", cartao(f, f.cartao, f.pagamento, "1000.00", 10, 20));
  }

  @Test
  void refusesToDeleteAContaThatHasACartaoOrPaysOne() {
    Fixture f = fixture();
    cartao(f, f.cartao, f.pagamento, "5000.00", 25, 5).update();

    assertConstraint(
        "cartao_conta_mesmo_usuario_e_tipo",
        jdbc.sql("DELETE FROM conta WHERE id = :id").param("id", f.cartao));
    assertConstraint(
        "cartao_pagamento_mesmo_usuario_e_tipo",
        jdbc.sql("DELETE FROM conta WHERE id = :id").param("id", f.pagamento));
  }

  @Test
  void deletesEverythingWhenTheUsuarioIsDeleted() {
    Fixture f = fixture();
    cartao(f, f.cartao, f.pagamento, "5000.00", 25, 5).update();

    int deleted = jdbc.sql("DELETE FROM usuario WHERE id = :id").param("id", f.usuario).update();

    assertThat(deleted).isEqualTo(1);
    assertThat(count("cartao", f.usuario)).isZero();
    assertThat(count("conta", f.usuario)).isZero();
  }

  private record Fixture(UUID usuario, UUID pagamento, UUID cartao) {}

  private Fixture fixture() {
    UUID usuario = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuario)
        .param("email", "cartao-" + usuario + "@example.com")
        .update();
    return new Fixture(usuario, conta(usuario, "corrente"), conta(usuario, "cartao_credito"));
  }

  private UUID conta(UUID usuario, String tipo) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuario, :nome, :tipo)")
        .param("id", id)
        .param("usuario", usuario)
        .param("nome", tipo + "-" + id)
        .param("tipo", tipo)
        .update();
    return id;
  }

  // o usuario_id é o de f: os testes de outro dono passam a conta alheia de propósito
  private JdbcClient.StatementSpec cartao(
      Fixture f, UUID conta, UUID pagamento, String limite, int fechamento, int vencimento) {
    return jdbc.sql(
            "INSERT INTO cartao (conta_id, usuario_id, limite, dia_fechamento, dia_vencimento,"
                + " conta_pagamento_id) VALUES (:conta, :usuario, CAST(:limite AS numeric),"
                + " :fechamento, :vencimento, :pagamento)")
        .param("conta", conta)
        .param("usuario", f.usuario)
        .param("limite", limite)
        .param("fechamento", fechamento)
        .param("vencimento", vencimento)
        .param("pagamento", pagamento);
  }

  private long count(String table, UUID usuario) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE usuario_id = :id")
        .param("id", usuario)
        .query(Long.class)
        .single();
  }

  private void assertConstraint(String constraint, JdbcClient.StatementSpec statement) {
    assertThatThrownBy(statement::update)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }
}
