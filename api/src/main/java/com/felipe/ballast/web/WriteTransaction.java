package com.felipe.ballast.web;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

// Transação das escritas de dinheiro (lançamento, transferência e recorrência). Abre com um prazo
// de 2 s para esperar trava: sem ele, uma transação presa segura as outras até o socketTimeout de
// 30 s. Estourar o prazo lança PessimisticLockingFailureException, que vira 503 (ADR-026).
// SET LOCAL vale só até o fim desta transação e tem de ser a primeira instrução, antes de
// qualquer trava.
@Component
public class WriteTransaction {

  static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(2);

  private final TransactionTemplate transaction;
  private final JdbcClient jdbc;

  WriteTransaction(TransactionTemplate transaction, JdbcClient jdbc) {
    this.transaction = transaction;
    this.jdbc = jdbc;
  }

  public <T> T execute(TransactionCallback<T> action) {
    return execute(DEFAULT_LOCK_TIMEOUT, action);
  }

  // Prazo menor que 2 s para quem não deve atrasar a escrita do usuário: a espera de quem chega
  // primeiro na fila de uma trava soma com a de quem chega depois (cada espera tem o prazo cheio).
  public <T> T execute(Duration lockTimeout, TransactionCallback<T> action) {
    return transaction.execute(
        status -> {
          // O valor é um número nosso, em milissegundos, nunca texto do cliente.
          jdbc.sql("SET LOCAL lock_timeout = '" + lockTimeout.toMillis() + "ms'").update();
          return action.doInTransaction(status);
        });
  }
}
