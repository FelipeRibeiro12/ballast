package com.felipe.ballast.entries;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Leitura em SQL (ADR-017). Toda consulta filtra por usuario_id.
@Repository
class TransferQueries {

  // As duas contas e as duas pernas de uma transferência, para o DELETE saber o que travar.
  record Legs(UUID outEntryId, UUID inEntryId, UUID fromAccountId, UUID toAccountId) {}

  private final JdbcClient jdbc;

  TransferQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<TransferSummary> find(UUID userId, UUID id) {
    return jdbc.sql(
            """
            SELECT t.id, t.conta_saida_id, t.conta_entrada_id, t.saida_id, t.entrada_id,
                   l.valor, l.data_caixa, l.titulo, l.descricao, l.criado_em
            FROM transferencia t
            JOIN lancamento l ON l.id = t.saida_id AND l.usuario_id = t.usuario_id
            WHERE t.usuario_id = :userId AND t.id = :id
            """)
        .param("userId", userId)
        .param("id", id)
        .query(TransferQueries::map)
        .optional();
  }

  // Trava a linha da transferência e devolve o que ela liga. Vazio para transferência alheia ou
  // inexistente. É a primeira trava do DELETE, antes das pernas e das contas.
  Optional<Legs> lockTransfer(UUID userId, UUID id) {
    return jdbc.sql(
            """
            SELECT saida_id, entrada_id, conta_saida_id, conta_entrada_id
            FROM transferencia
            WHERE usuario_id = :userId AND id = :id
            FOR UPDATE
            """)
        .param("userId", userId)
        .param("id", id)
        .query(
            (rs, row) ->
                new Legs(
                    rs.getObject("saida_id", UUID.class),
                    rs.getObject("entrada_id", UUID.class),
                    rs.getObject("conta_saida_id", UUID.class),
                    rs.getObject("conta_entrada_id", UUID.class)))
        .optional();
  }

  // Trava as duas pernas em ordem de id, a mesma do gatilho da V7 e do PUT/DELETE de lançamento.
  void lockEntries(UUID userId, List<UUID> entryIds) {
    jdbc.sql(
            "SELECT id FROM lancamento WHERE usuario_id = :userId AND id IN (:ids)"
                + " ORDER BY id FOR UPDATE")
        .param("userId", userId)
        .param("ids", entryIds)
        .query(UUID.class)
        .list();
  }

  private static TransferSummary map(ResultSet rs, int row) throws SQLException {
    return new TransferSummary(
        rs.getObject("id", UUID.class),
        rs.getObject("conta_saida_id", UUID.class),
        rs.getObject("conta_entrada_id", UUID.class),
        rs.getBigDecimal("valor"),
        rs.getObject("data_caixa", LocalDate.class),
        rs.getString("titulo"),
        rs.getString("descricao"),
        rs.getObject("saida_id", UUID.class),
        rs.getObject("entrada_id", UUID.class),
        rs.getObject("criado_em", OffsetDateTime.class).toInstant());
  }
}
