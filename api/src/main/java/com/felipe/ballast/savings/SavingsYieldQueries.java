package com.felipe.ballast.savings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017). Tudo é escopado por usuario_id, menos a listagem do job, que só devolve
// pares (usuário, conta) e depois trabalha conta por conta.
@Repository
class SavingsYieldQueries {

  // percent é nulo enquanto o usuário não informou o percentual do CDI.
  record Pot(BigDecimal percent) {}

  record PotRef(UUID userId, UUID accountId) {}

  private final JdbcClient jdbc;

  SavingsYieldQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Trava a conta antes de qualquer outra coisa (ADR-057). Vazio para conta de outro usuário,
  // inexistente, arquivada ou que não é cofrinho, sem distinguir.
  Optional<Pot> lockPot(UUID userId, UUID accountId) {
    return jdbc.sql(
            """
            SELECT percentual_cdi FROM conta
            WHERE usuario_id = :userId AND id = :accountId AND tipo = 'cofrinho' AND NOT arquivada
            FOR UPDATE
            """)
        .param("userId", userId)
        .param("accountId", accountId)
        .query((rs, row) -> new Pot(rs.getBigDecimal("percentual_cdi")))
        .optional();
  }

  boolean hasMarker(UUID userId, UUID accountId, LocalDate day) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM rendimento_cofrinho
              WHERE usuario_id = :userId AND conta_id = :accountId AND data = :day)
            """)
        .param("userId", userId)
        .param("accountId", accountId)
        .param("day", day)
        .query(Boolean.class)
        .single();
  }

  Optional<LocalDate> lastMarker(UUID userId, UUID accountId) {
    return jdbc.sql(
            """
            SELECT max(data) FROM rendimento_cofrinho
            WHERE usuario_id = :userId AND conta_id = :accountId
            """)
        .param("userId", userId)
        .param("accountId", accountId)
        .query(LocalDate.class)
        .optional();
  }

  // A mesma fórmula do saldo realizado do Posso gastar (SpendableQueries.savingsPots), cortada em
  // data_caixa < before.
  BigDecimal realizedBalanceBefore(UUID userId, UUID accountId, LocalDate before) {
    return jdbc.sql(
            """
            SELECT c.saldo_inicial + COALESCE(SUM(CASE l.tipo
                WHEN 'receita' THEN l.valor
                WHEN 'despesa' THEN -l.valor
                WHEN 'transferencia' THEN CASE WHEN te.id IS NOT NULL THEN l.valor ELSE -l.valor END
              END), 0) AS saldo
            FROM conta c
            LEFT JOIN lancamento l ON l.conta_id = c.id AND l.usuario_id = c.usuario_id
              AND l.status = 'realizado' AND l.data_caixa < :before
            LEFT JOIN transferencia te ON te.entrada_id = l.id AND te.usuario_id = l.usuario_id
            WHERE c.usuario_id = :userId AND c.id = :accountId
            GROUP BY c.id
            """)
        .param("userId", userId)
        .param("accountId", accountId)
        .param("before", before)
        .query(BigDecimal.class)
        .single();
  }

  // Categoria semente do dono. Se ele criou uma filha com o mesmo nome, a raiz vence.
  Optional<UUID> incomeCategory(UUID userId, String name) {
    return jdbc.sql(
            """
            SELECT id FROM categoria
            WHERE usuario_id = :userId AND nome = :name AND tipo = 'receita'
            ORDER BY (pai_id IS NULL) DESC, id
            LIMIT 1
            """)
        .param("userId", userId)
        .param("name", name)
        .query(UUID.class)
        .optional();
  }

  void insertMarker(UUID id, UUID userId, UUID accountId, LocalDate day, UUID entryId) {
    jdbc.sql(
            """
            INSERT INTO rendimento_cofrinho (id, usuario_id, conta_id, data, lancamento_id)
            VALUES (:id, :userId, :accountId, :day, :entryId)
            """)
        .param("id", id)
        .param("userId", userId)
        .param("accountId", accountId)
        .param("day", day)
        .param("entryId", entryId)
        .update();
  }

  // Em lotes pela chave primária, como o RecurrenceJob: a memória não cresce com o número de
  // contas.
  List<PotRef> activePots(UUID afterAccountId, int limit) {
    return jdbc.sql(
            """
            SELECT usuario_id, id FROM conta
            WHERE tipo = 'cofrinho' AND NOT arquivada AND percentual_cdi IS NOT NULL
              AND id > :after
            ORDER BY id
            LIMIT :limit
            """)
        .param("after", afterAccountId)
        .param("limit", limit)
        .query(
            (rs, row) ->
                new PotRef(rs.getObject("usuario_id", UUID.class), rs.getObject("id", UUID.class)))
        .list();
  }
}
