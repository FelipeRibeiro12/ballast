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

// V26 (ADR-057): conta de cartão não é perna de saída de transferência, e o banco recusa.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransferenciaCartaoMigrationTest {

  @Autowired JdbcClient jdbc;

  private UUID user() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", id)
        .param("email", "cartao-" + id + "@example.com")
        .update();
    return id;
  }

  private UUID account(UUID user, String type) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :u, :nome, :tipo)")
        .param("id", id)
        .param("u", user)
        .param("nome", "Conta " + id)
        .param("tipo", type)
        .update();
    return id;
  }

  private UUID leg(UUID user, UUID account) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO lancamento (id, usuario_id, tipo, titulo, conta_id, valor,"
                + " data_competencia, data_caixa) VALUES (:id, :u, 'transferencia', 'x', :a, 10,"
                + " DATE '2026-10-01', DATE '2026-10-01')")
        .param("id", id)
        .param("u", user)
        .param("a", account)
        .update();
    return id;
  }

  private int transfer(UUID user, UUID fromAccount, UUID toAccount) {
    return jdbc.sql(
            "INSERT INTO transferencia (id, usuario_id, saida_id, entrada_id, conta_saida_id,"
                + " conta_entrada_id) VALUES (:id, :u, :saida, :entrada, :de, :para)")
        .param("id", UUID.randomUUID())
        .param("u", user)
        .param("saida", leg(user, fromAccount))
        .param("entrada", leg(user, toAccount))
        .param("de", fromAccount)
        .param("para", toAccount)
        .update();
  }

  @Test
  void refusesACardAccountAsTheOutgoingLeg() {
    UUID user = user();
    UUID card = account(user, "cartao_credito");
    UUID checking = account(user, "corrente");

    assertThatThrownBy(() -> transfer(user, card, checking))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("transferencia_saida_nao_e_cartao");
  }

  @Test
  void acceptsACardAccountAsTheIncomingLegBecauseThatIsHowAnInvoiceIsPaid() {
    UUID user = user();
    UUID card = account(user, "cartao_credito");
    UUID checking = account(user, "corrente");

    assertThat(transfer(user, checking, card)).isEqualTo(1);
  }

  @Test
  void refusesToTurnAnAccountWithAnOutgoingTransferIntoACard() {
    UUID user = user();
    UUID checking = account(user, "corrente");
    UUID savings = account(user, "poupanca");
    transfer(user, checking, savings);

    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE conta SET tipo = 'cartao_credito' WHERE id = :id")
                    .param("id", checking)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
