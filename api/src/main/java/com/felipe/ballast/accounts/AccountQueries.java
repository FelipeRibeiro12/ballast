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
// Saldo: hoje é só o saldo_inicial. A F3 troca a expressão por saldo_inicial mais a soma dos
// lançamentos realizados (LEFT JOIN agregado por conta_id), nesta mesma consulta, para a
// listagem continuar com uma ida ao banco só.
@Repository
class AccountQueries {

  private static final String COLUMNS =
      """
      SELECT c.id, c.nome, c.tipo, c.moeda, c.saldo_inicial, c.saldo_inicial AS saldo,
             c.arquivada, c.criada_em
      FROM conta c
      """;

  private final JdbcClient jdbc;

  AccountQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  List<AccountSummary> list(UUID userId, boolean archived) {
    return jdbc.sql(
            COLUMNS
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
            COLUMNS
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
