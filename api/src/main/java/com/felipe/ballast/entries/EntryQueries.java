package com.felipe.ballast.entries;

import java.math.BigDecimal;
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

  // Trava a linha da conta até o fim da transação. Vazio quando a conta não existe ou é de outro
  // usuário; senão, se está arquivada. Precisa rodar dentro de uma transação: fora dela a trava
  // solta no mesmo instante. Quem arquiva faz UPDATE nesta mesma linha e espera na fila.
  Optional<Boolean> lockAccount(UUID userId, UUID accountId) {
    return jdbc.sql(
            "SELECT arquivada FROM conta WHERE usuario_id = :userId AND id = :id FOR UPDATE")
        .param("userId", userId)
        .param("id", accountId)
        .query(Boolean.class)
        .optional();
  }

  // Trava o lançamento do dono e devolve a conta em que ele está agora. Vazio para lançamento
  // alheio ou inexistente. Segura o PUT e o DELETE do mesmo lançamento um atrás do outro, para a
  // conta lida aqui não mudar antes de ela ser travada.
  Optional<UUID> lockEntryAccount(UUID userId, UUID entryId) {
    return jdbc.sql(
            "SELECT conta_id FROM lancamento WHERE usuario_id = :userId AND id = :id FOR UPDATE")
        .param("userId", userId)
        .param("id", entryId)
        .query(UUID.class)
        .optional();
  }

  // Saldo atual da conta na transação em curso (ADR-024), em reais. Mesma conta que o
  // AccountQueries, só que para uma conta e sem o resto das colunas.
  BigDecimal balance(UUID userId, UUID accountId) {
    return jdbc.sql(
            """
            SELECT c.saldo_inicial + COALESCE(
                     (SELECT SUM(CASE WHEN l.tipo = 'receita' THEN l.valor ELSE -l.valor END)
                      FROM lancamento l
                      WHERE l.usuario_id = :userId AND l.conta_id = :id
                        AND l.status = 'realizado'), 0)
            FROM conta c
            WHERE c.usuario_id = :userId AND c.id = :id
            """)
        .param("userId", userId)
        .param("id", accountId)
        .query(BigDecimal.class)
        .single();
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
