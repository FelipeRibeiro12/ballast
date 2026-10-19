package com.felipe.ballast.accounts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Leitura com saldo, em SQL (ADR-017). Toda consulta filtra por usuario_id.
// Saldo: saldo_inicial mais receitas realizadas menos despesas realizadas (ADR-024), calculado
// aqui para a listagem fazer uma ida ao banco só. Nunca é coluna.
@Repository
class AccountQueries {

  // MATERIALIZED força um agregado só por consulta. Sem ele o planejador pode refazer o agregado
  // para cada conta (nested loop) quando subestima as linhas.
  private static final String MOVEMENT_START =
      """
      WITH movement AS MATERIALIZED (
        SELECT conta_id,
               SUM(CASE WHEN tipo = 'receita' THEN valor ELSE -valor END) AS movimento
        FROM lancamento
        WHERE usuario_id = :userId AND status = 'realizado'
      """;

  private static final String MOVEMENT_END =
      """
        GROUP BY conta_id
      )
      SELECT c.id, c.nome, c.tipo, c.moeda, c.saldo_inicial,
             c.saldo_inicial + COALESCE(m.movimento, 0) AS saldo,
             c.arquivada, c.criada_em
      FROM conta c
      LEFT JOIN movement m ON m.conta_id = c.id
      """;

  private static final String LIST_COLUMNS = MOVEMENT_START + MOVEMENT_END;
  // O detalhe filtra o agregado também pela conta pedida, para não somar o usuário inteiro.
  private static final String FIND_COLUMNS =
      MOVEMENT_START + "  AND conta_id = :id\n" + MOVEMENT_END;

  private final JdbcClient jdbc;

  AccountQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  List<AccountSummary> list(UUID userId, boolean archived) {
    return jdbc.sql(
            LIST_COLUMNS
                + """
                WHERE c.usuario_id = :userId AND c.arquivada = :archived
                ORDER BY lower(c.nome), c.id
                """)
        .param("userId", userId)
        .param("archived", archived)
        .query(AccountQueries::map)
        .list();
  }

  Optional<AccountSummary> find(UUID userId, UUID id) {
    return jdbc.sql(
            FIND_COLUMNS
                + """
                WHERE c.usuario_id = :userId AND c.id = :id
                """)
        .param("userId", userId)
        .param("id", id)
        .query(AccountQueries::map)
        .optional();
  }

  private static AccountSummary map(ResultSet rs, int row) throws SQLException {
    return new AccountSummary(
        rs.getObject("id", UUID.class),
        rs.getString("nome"),
        AccountType.fromValue(rs.getString("tipo")),
        rs.getString("moeda"),
        rs.getBigDecimal("saldo_inicial"),
        rs.getBigDecimal("saldo"),
        rs.getBoolean("arquivada"),
        rs.getObject("criada_em", OffsetDateTime.class).toInstant());
  }
}
