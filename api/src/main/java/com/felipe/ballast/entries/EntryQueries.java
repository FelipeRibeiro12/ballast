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

// Leitura em SQL (ADR-017). Toda consulta filtra por usuario_id. Conta e categoria são lidas
// direto da tabela, sem depender dos pacotes accounts e categories: o dono também é garantido
// pelas FKs compostas do banco, esta leitura só dá a mensagem certa antes de gravar.
@Repository
class EntryQueries {

  private static final String COLUMNS =
      """
      SELECT id, tipo, titulo, descricao, valor, categoria_id, conta_id, data_competencia,
             data_caixa, status, criado_em
      FROM lancamento
      """;

  private static final String ORDER = " ORDER BY data_caixa DESC, criado_em DESC, id DESC";

  private final JdbcClient jdbc;

  EntryQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<EntrySummary> find(UUID userId, UUID id) {
    return jdbc.sql(COLUMNS + "WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .query(EntryQueries::map)
        .optional();
  }

  // Período por data_caixa, pontas incluídas. accountId nulo não filtra por conta.
  List<EntrySummary> list(UUID userId, LocalDate from, LocalDate to, UUID accountId) {
    String where = "WHERE usuario_id = :userId AND data_caixa BETWEEN :from AND :to";
    if (accountId == null) {
      return jdbc.sql(COLUMNS + where + ORDER)
          .param("userId", userId)
          .param("from", from)
          .param("to", to)
          .query(EntryQueries::map)
          .list();
    }
    return jdbc.sql(COLUMNS + where + " AND conta_id = :accountId" + ORDER)
        .param("userId", userId)
        .param("from", from)
        .param("to", to)
        .param("accountId", accountId)
        .query(EntryQueries::map)
        .list();
  }

  // Vazio quando a conta não existe ou é de outro usuário; senão, se está arquivada.
  Optional<Boolean> findAccountArchived(UUID userId, UUID accountId) {
    return jdbc.sql("SELECT arquivada FROM conta WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", accountId)
        .query(Boolean.class)
        .optional();
  }

  // Confere dono e tipo juntos: categoria de receita não serve para despesa.
  boolean categoryExists(UUID userId, UUID categoryId, EntryType type) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM categoria WHERE usuario_id = :userId AND id = :id"
                + " AND tipo = :type)")
        .param("userId", userId)
        .param("id", categoryId)
        .param("type", type.value())
        .query(Boolean.class)
        .single();
  }

  private static EntrySummary map(ResultSet rs, int row) throws SQLException {
    return new EntrySummary(
        rs.getObject("id", UUID.class),
        EntryType.fromValue(rs.getString("tipo")),
        rs.getString("titulo"),
        rs.getString("descricao"),
        rs.getBigDecimal("valor"),
        rs.getObject("categoria_id", UUID.class),
        rs.getObject("conta_id", UUID.class),
        rs.getObject("data_competencia", LocalDate.class),
        rs.getObject("data_caixa", LocalDate.class),
        rs.getString("status"),
        rs.getObject("criado_em", OffsetDateTime.class).toInstant());
  }
}
