package com.felipe.ballast.thirdparties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017). Toda consulta filtra por usuario_id; as FKs compostas da V29 seguram a
// corrida de dono.
@Repository
class ThirdPartyQueries {

  // bought: despesas não canceladas. received: reembolsos já realizados.
  record ThirdParty(
      UUID id,
      String name,
      boolean archived,
      BigDecimal bought,
      BigDecimal received,
      BigDecimal boughtUntilMonthEnd) {}

  record TaggedEntry(
      UUID id,
      String title,
      String type,
      BigDecimal amount,
      LocalDate cashDate,
      String status,
      Integer installment,
      Integer installmentTotal) {}

  enum EntryKind {
    NOT_FOUND,
    TRANSFER,
    OK
  }

  private static final String SELECT_THIRD_PARTY =
      """
      SELECT t.id, t.nome, t.arquivado,
        COALESCE(SUM(l.valor) FILTER (WHERE l.tipo = 'despesa'), 0) AS comprado,
        COALESCE(SUM(l.valor) FILTER (WHERE l.tipo = 'receita' AND l.status = 'realizado'), 0)
          AS recebido,
        COALESCE(SUM(l.valor) FILTER (WHERE l.tipo = 'despesa'
          AND l.data_caixa <= (date_trunc('month', now() AT TIME ZONE 'America/Sao_Paulo')
                               + interval '1 month - 1 day')::date), 0) AS comprado_ate_fim_do_mes
      FROM terceiro t
      LEFT JOIN terceiro_lancamento v ON v.terceiro_id = t.id AND v.usuario_id = t.usuario_id
      LEFT JOIN lancamento l ON l.id = v.lancamento_id AND l.usuario_id = v.usuario_id
        AND l.status <> 'cancelado'
      """;

  private final JdbcClient jdbc;

  ThirdPartyQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  List<ThirdParty> list(UUID userId) {
    return jdbc.sql(
            SELECT_THIRD_PARTY
                + " WHERE t.usuario_id = :userId GROUP BY t.id"
                + " ORDER BY t.arquivado, lower(t.nome), t.id")
        .param("userId", userId)
        .query(ThirdPartyQueries::map)
        .list();
  }

  Optional<ThirdParty> find(UUID userId, UUID id) {
    return jdbc.sql(
            SELECT_THIRD_PARTY + " WHERE t.usuario_id = :userId AND t.id = :id GROUP BY t.id")
        .param("userId", userId)
        .param("id", id)
        .query(ThirdPartyQueries::map)
        .optional();
  }

  UUID insert(UUID userId, String name) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO terceiro (id, usuario_id, nome) VALUES (:id, :userId, :name)")
        .param("id", id)
        .param("userId", userId)
        .param("name", name)
        .update();
    return id;
  }

  int update(UUID userId, UUID id, String name, boolean archived) {
    return jdbc.sql(
            "UPDATE terceiro SET nome = :name, arquivado = :archived"
                + " WHERE usuario_id = :userId AND id = :id")
        .param("name", name)
        .param("archived", archived)
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  boolean hasEntries(UUID userId, UUID id) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM terceiro_lancamento"
                + " WHERE usuario_id = :userId AND terceiro_id = :id)")
        .param("userId", userId)
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  int delete(UUID userId, UUID id) {
    return jdbc.sql("DELETE FROM terceiro WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  // Trava o terceiro para a ligação não correr contra o arquivar.
  Optional<Boolean> lockArchived(UUID userId, UUID id) {
    return jdbc.sql(
            "SELECT arquivado FROM terceiro WHERE usuario_id = :userId AND id = :id FOR SHARE")
        .param("userId", userId)
        .param("id", id)
        .query(Boolean.class)
        .optional();
  }

  EntryKind kindOf(UUID userId, UUID entryId) {
    Optional<String> type =
        jdbc.sql("SELECT tipo FROM lancamento WHERE usuario_id = :userId AND id = :id")
            .param("userId", userId)
            .param("id", entryId)
            .query(String.class)
            .optional();
    if (type.isEmpty()) {
      return EntryKind.NOT_FOUND;
    }
    return type.get().equals("transferencia") ? EntryKind.TRANSFER : EntryKind.OK;
  }

  void tagEntry(UUID userId, UUID entryId, UUID thirdPartyId) {
    jdbc.sql(
            """
            INSERT INTO terceiro_lancamento (lancamento_id, usuario_id, terceiro_id)
            VALUES (:entryId, :userId, :thirdPartyId)
            ON CONFLICT (lancamento_id) DO UPDATE SET terceiro_id = EXCLUDED.terceiro_id
            """)
        .param("entryId", entryId)
        .param("userId", userId)
        .param("thirdPartyId", thirdPartyId)
        .update();
  }

  // Todas as parcelas da compra de uma vez. Zero linhas quer dizer compra que não existe.
  int tagPurchase(UUID userId, UUID purchaseId, UUID thirdPartyId) {
    return jdbc.sql(
            """
            INSERT INTO terceiro_lancamento (lancamento_id, usuario_id, terceiro_id)
            SELECT id, usuario_id, :thirdPartyId FROM lancamento
            WHERE usuario_id = :userId AND parcela_de_id = :purchaseId
            ON CONFLICT (lancamento_id) DO UPDATE SET terceiro_id = EXCLUDED.terceiro_id
            """)
        .param("thirdPartyId", thirdPartyId)
        .param("userId", userId)
        .param("purchaseId", purchaseId)
        .update();
  }

  void untagEntry(UUID userId, UUID entryId) {
    jdbc.sql("DELETE FROM terceiro_lancamento WHERE usuario_id = :userId AND lancamento_id = :id")
        .param("userId", userId)
        .param("id", entryId)
        .update();
  }

  int untagPurchase(UUID userId, UUID purchaseId) {
    return jdbc.sql(
            """
            DELETE FROM terceiro_lancamento v USING lancamento l
            WHERE v.usuario_id = :userId AND l.id = v.lancamento_id AND l.usuario_id = v.usuario_id
              AND l.parcela_de_id = :purchaseId
            """)
        .param("userId", userId)
        .param("purchaseId", purchaseId)
        .update();
  }

  boolean purchaseExists(UUID userId, UUID purchaseId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM lancamento"
                + " WHERE usuario_id = :userId AND parcela_de_id = :purchaseId)")
        .param("userId", userId)
        .param("purchaseId", purchaseId)
        .query(Boolean.class)
        .single();
  }

  List<TaggedEntry> entries(UUID userId, UUID thirdPartyId) {
    return jdbc.sql(
            """
            SELECT l.id, l.titulo, l.tipo, l.valor, l.data_caixa, l.status, l.parcela_num,
                   l.parcela_total
            FROM terceiro_lancamento v
            JOIN lancamento l ON l.id = v.lancamento_id AND l.usuario_id = v.usuario_id
            WHERE v.usuario_id = :userId AND v.terceiro_id = :thirdPartyId
            ORDER BY l.data_caixa DESC, l.id DESC
            LIMIT 200
            """)
        .param("userId", userId)
        .param("thirdPartyId", thirdPartyId)
        .query(
            (rs, row) ->
                new TaggedEntry(
                    rs.getObject("id", UUID.class),
                    rs.getString("titulo"),
                    rs.getString("tipo"),
                    rs.getBigDecimal("valor"),
                    rs.getObject("data_caixa", LocalDate.class),
                    rs.getString("status"),
                    (Integer) rs.getObject("parcela_num"),
                    (Integer) rs.getObject("parcela_total")))
        .list();
  }

  private static ThirdParty map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new ThirdParty(
        rs.getObject("id", UUID.class),
        rs.getString("nome"),
        rs.getBoolean("arquivado"),
        rs.getBigDecimal("comprado"),
        rs.getBigDecimal("recebido"),
        rs.getBigDecimal("comprado_ate_fim_do_mes"));
  }
}
