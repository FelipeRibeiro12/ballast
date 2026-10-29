package com.felipe.ballast.imports;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto, sempre com usuario_id (ADR-017). Texto de linha, memo e FITID passam só como
// parâmetro, nunca concatenados.
@Repository
class ImportQueries {

  // Teto da lista de importações; as pendentes já são no máximo 20.
  static final int LIST_LIMIT = 100;
  // Candidatos à conciliação vêm de uma janela de datas da conta; o teto protege a memória.
  private static final int CANDIDATE_LIMIT = 20_000;

  record AccountInfo(boolean archived, String type) {}

  record ImportRow(
      UUID id,
      UUID accountId,
      String status,
      int totalLines,
      int totalInvalid,
      Instant createdAt,
      Instant confirmedAt,
      int toImport,
      int toReconcile,
      int toIgnore) {}

  record LineRow(
      UUID id,
      int order,
      LocalDate date,
      String type,
      BigDecimal amount,
      String title,
      String description,
      String invalidReason,
      String proposal,
      String reason,
      String decision,
      UUID entryId,
      UUID categoryId,
      String result,
      String externalHash,
      UUID defaultCategoryId) {}

  private static final String IMPORT_COLUMNS =
      """
      SELECT i.id, i.conta_id, i.status, i.total_linhas, i.total_invalidas, i.criado_em,
             i.confirmada_em,
             count(*) FILTER (WHERE COALESCE(l.decisao, l.proposta) = 'importar') AS importar,
             count(*) FILTER (WHERE COALESCE(l.decisao, l.proposta) = 'conciliar') AS conciliar,
             count(*) FILTER (WHERE COALESCE(l.decisao, l.proposta) = 'ignorar') AS ignorar
      FROM importacao i
      LEFT JOIN importacao_linha l ON l.importacao_id = i.id AND l.usuario_id = i.usuario_id
      WHERE i.usuario_id = :userId
      """;

  // A categoria padrão sai da semente marcada (V24), que sobrevive a renomear.
  private static final String LINE_COLUMNS =
      """
      SELECT l.id, l.ordem, l.data, l.tipo, l.valor, l.titulo, l.descricao, l.invalida_motivo,
             l.proposta, l.proposta_motivo, l.decisao, l.lancamento_id, l.categoria_id,
             l.resultado, l.hash_externo,
             (SELECT c.id FROM categoria c
              WHERE c.usuario_id = l.usuario_id
                AND c.semente = CASE l.tipo WHEN 'receita' THEN 'outras_receitas'
                                            WHEN 'despesa' THEN 'outros' END) AS categoria_padrao
      FROM importacao_linha l
      WHERE l.usuario_id = :userId
      """;

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batchJdbc;

  ImportQueries(JdbcClient jdbc, NamedParameterJdbcTemplate batchJdbc) {
    this.jdbc = jdbc;
    this.batchJdbc = batchJdbc;
  }

  UUID newId() {
    return UuidVersion7Strategy.INSTANCE.generateUuid(null);
  }

  Optional<AccountInfo> findAccount(UUID userId, UUID accountId) {
    return jdbc.sql("SELECT arquivada, tipo FROM conta WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", accountId)
        .query((rs, row) -> new AccountInfo(rs.getBoolean("arquivada"), rs.getString("tipo")))
        .optional();
  }

  Optional<UUID> findIdByFileHash(UUID userId, String fileHash) {
    return jdbc.sql("SELECT id FROM importacao WHERE usuario_id = :userId AND hash_arquivo = :hash")
        .param("userId", userId)
        .param("hash", fileHash)
        .query(UUID.class)
        .optional();
  }

  // Quais dos hashes já estão em lancamento (importado ou conciliado antes).
  Set<String> existingExternalHashes(UUID userId, Collection<String> hashes) {
    if (hashes.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        jdbc.sql(
                "SELECT hash_externo FROM lancamento"
                    + " WHERE usuario_id = :userId AND hash_externo IN (:hashes)")
            .param("userId", userId)
            .param("hashes", hashes)
            .query(String.class)
            .list());
  }

  // Lançamento realizado da conta, ainda sem hash, numa janela de data_competencia. Perna de
  // transferência fica de fora pelo tipo.
  List<ImportPlanner.Candidate> candidates(
      UUID userId, UUID accountId, LocalDate from, LocalDate to) {
    return jdbc.sql(
            """
            SELECT id, tipo, valor, data_competencia FROM lancamento
            WHERE usuario_id = :userId AND conta_id = :accountId
              AND tipo IN ('receita', 'despesa') AND status = 'realizado'
              AND hash_externo IS NULL
              AND data_competencia BETWEEN :from AND :to
            ORDER BY data_competencia, id
            LIMIT :limit
            """)
        .param("userId", userId)
        .param("accountId", accountId)
        .param("from", from)
        .param("to", to)
        .param("limit", CANDIDATE_LIMIT)
        .query(
            (rs, row) ->
                new ImportPlanner.Candidate(
                    rs.getObject("id", UUID.class),
                    rs.getString("tipo"),
                    rs.getBigDecimal("valor"),
                    rs.getObject("data_competencia", LocalDate.class)))
        .list();
  }

  void insertImport(
      UUID id, UUID userId, UUID accountId, String fileHash, int totalLines, int totalInvalid) {
    jdbc.sql(
            "INSERT INTO importacao (id, usuario_id, conta_id, hash_arquivo, total_linhas,"
                + " total_invalidas) VALUES (:id, :userId, :accountId, :hash, :lines, :invalid)")
        .param("id", id)
        .param("userId", userId)
        .param("accountId", accountId)
        .param("hash", fileHash)
        .param("lines", totalLines)
        .param("invalid", totalInvalid)
        .update();
  }

  // Em lote: um arquivo de milhares de linhas não vira milhares de idas ao banco.
  void insertLines(
      UUID userId, UUID importId, List<ImportPlanner.PlannedLine> lines, List<String> rawJsons) {
    SqlParameterSource[] batch = new SqlParameterSource[lines.size()];
    for (int i = 0; i < batch.length; i++) {
      ImportPlanner.PlannedLine line = lines.get(i);
      OfxTransaction tx = line.transaction();
      batch[i] =
          new MapSqlParameterSource()
              .addValue("id", newId())
              .addValue("userId", userId)
              .addValue("importId", importId)
              .addValue("order", tx.order())
              .addValue("raw", rawJsons.get(i))
              .addValue("hash", line.externalHash())
              .addValue("date", tx.date())
              .addValue("type", line.type())
              .addValue("amount", line.amount())
              .addValue("title", line.title())
              .addValue("description", line.description())
              .addValue("invalid", tx.invalidReason())
              .addValue("proposal", line.proposal())
              .addValue("reason", line.reason())
              .addValue("entryId", line.entryId());
    }
    batchJdbc.batchUpdate(
        """
        INSERT INTO importacao_linha (id, usuario_id, importacao_id, ordem, bruta, hash_externo,
            data, tipo, valor, titulo, descricao, invalida_motivo, proposta, proposta_motivo,
            lancamento_id)
        VALUES (:id, :userId, :importId, :order, CAST(:raw AS jsonb), :hash, :date, :type,
            :amount, :title, :description, :invalid, :proposal, :reason, :entryId)
        """,
        batch);
  }

  Optional<ImportRow> findImport(UUID userId, UUID id) {
    return jdbc.sql(IMPORT_COLUMNS + " AND i.id = :id GROUP BY i.id")
        .param("userId", userId)
        .param("id", id)
        .query(ImportQueries::mapImport)
        .optional();
  }

  List<ImportRow> listImports(UUID userId) {
    return jdbc.sql(
            IMPORT_COLUMNS
                + " GROUP BY i.id ORDER BY i.criado_em DESC, i.id DESC LIMIT "
                + LIST_LIMIT)
        .param("userId", userId)
        .query(ImportQueries::mapImport)
        .list();
  }

  List<LineRow> lines(UUID userId, UUID importId) {
    return jdbc.sql(LINE_COLUMNS + " AND l.importacao_id = :importId ORDER BY l.ordem")
        .param("userId", userId)
        .param("importId", importId)
        .query(ImportQueries::mapLine)
        .list();
  }

  Optional<LineRow> findLine(UUID userId, UUID importId, UUID lineId) {
    return jdbc.sql(LINE_COLUMNS + " AND l.importacao_id = :importId AND l.id = :id")
        .param("userId", userId)
        .param("importId", importId)
        .param("id", lineId)
        .query(ImportQueries::mapLine)
        .optional();
  }

  record LockedImport(String status, UUID accountId) {}

  // Trava a linha da importação até o fim da transação. Vazio quando não existe ou é de outro
  // usuário. A confirmação trava para escrita; editar uma linha trava para leitura, o que deixa
  // duas edições andarem juntas e faz a confirmação esperar por elas.
  Optional<LockedImport> lockImport(UUID userId, UUID id, boolean exclusive) {
    return jdbc.sql(
            "SELECT status, conta_id FROM importacao WHERE usuario_id = :userId AND id = :id FOR "
                + (exclusive ? "UPDATE" : "SHARE"))
        .param("userId", userId)
        .param("id", id)
        .query(
            (rs, row) ->
                new LockedImport(rs.getString("status"), rs.getObject("conta_id", UUID.class)))
        .optional();
  }

  // Lançamento que ainda pode ser conciliado: do usuário, da conta da importação, realizado e
  // sem hash. Mesmos critérios dos candidatos da proposta.
  boolean isReconcilable(UUID userId, UUID accountId, UUID entryId) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM lancamento
                           WHERE usuario_id = :userId AND id = :id AND conta_id = :accountId
                             AND tipo IN ('receita', 'despesa') AND status = 'realizado'
                             AND hash_externo IS NULL)
            """)
        .param("userId", userId)
        .param("id", entryId)
        .param("accountId", accountId)
        .query(Boolean.class)
        .single();
  }

  // Tipo da categoria do usuário, vazio se não existe ou é de outro.
  Optional<String> categoryType(UUID userId, UUID categoryId) {
    return jdbc.sql("SELECT tipo FROM categoria WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", categoryId)
        .query(String.class)
        .optional();
  }

  // titulo nulo mantém o atual. O vínculo e a categoria da decisão anterior não sobram.
  int updateDecision(
      UUID userId,
      UUID importId,
      UUID lineId,
      String decision,
      UUID entryId,
      UUID categoryId,
      String title) {
    return jdbc.sql(
            """
            UPDATE importacao_linha
            SET decisao = :decision, lancamento_id = :entryId, categoria_id = :categoryId,
                titulo = COALESCE(:title, titulo)
            WHERE usuario_id = :userId AND importacao_id = :importId AND id = :id
            """)
        .param("userId", userId)
        .param("importId", importId)
        .param("id", lineId)
        .param("decision", decision)
        .param("entryId", entryId)
        .param("categoryId", categoryId)
        .param("title", title)
        .update();
  }

  // Uma confirmação por usuário de cada vez. Duas importações com o mesmo FITID confirmadas em
  // corrida se enxergariam sem o hash um do outro e bateriam no unique de lancamento; com a trava,
  // a segunda já lê o hash da primeira e marca a linha como duplicada. A trava vale até o commit.
  // Tenta sem esperar: quem perde responde 409 em vez de prender uma conexão do pool.
  boolean tryLockConfirmations(UUID userId) {
    return jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", userId + ":importacao-confirmacao")
        .query(Boolean.class)
        .single();
  }

  // Grava o hash no lançamento que já existia e devolve 0 se ele sumiu, mudou de conta ou já
  // tem hash.
  int stampEntry(UUID userId, UUID accountId, UUID entryId, String hash, UUID lineId) {
    return jdbc.sql(
            """
            UPDATE lancamento SET hash_externo = :hash, importacao_linha_id = :lineId
            WHERE usuario_id = :userId AND id = :id AND conta_id = :accountId
              AND tipo IN ('receita', 'despesa') AND status = 'realizado'
              AND hash_externo IS NULL
            """)
        .param("hash", hash)
        .param("lineId", lineId)
        .param("userId", userId)
        .param("id", entryId)
        .param("accountId", accountId)
        .update();
  }

  void setResult(UUID userId, UUID lineId, String result, UUID entryId) {
    jdbc.sql(
            "UPDATE importacao_linha SET resultado = :result, lancamento_id = :entryId"
                + " WHERE usuario_id = :userId AND id = :id")
        .param("result", result)
        .param("entryId", entryId)
        .param("userId", userId)
        .param("id", lineId)
        .update();
  }

  void markConfirmed(UUID userId, UUID id) {
    jdbc.sql(
            "UPDATE importacao SET status = 'confirmada', confirmada_em = now()"
                + " WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  int deleteImport(UUID userId, UUID id) {
    return jdbc.sql("DELETE FROM importacao WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  private static ImportRow mapImport(ResultSet rs, int row) throws SQLException {
    OffsetDateTime confirmed = rs.getObject("confirmada_em", OffsetDateTime.class);
    return new ImportRow(
        rs.getObject("id", UUID.class),
        rs.getObject("conta_id", UUID.class),
        rs.getString("status"),
        rs.getInt("total_linhas"),
        rs.getInt("total_invalidas"),
        rs.getObject("criado_em", OffsetDateTime.class).toInstant(),
        confirmed == null ? null : confirmed.toInstant(),
        rs.getInt("importar"),
        rs.getInt("conciliar"),
        rs.getInt("ignorar"));
  }

  private static LineRow mapLine(ResultSet rs, int row) throws SQLException {
    return new LineRow(
        rs.getObject("id", UUID.class),
        rs.getInt("ordem"),
        rs.getObject("data", LocalDate.class),
        rs.getString("tipo"),
        rs.getBigDecimal("valor"),
        rs.getString("titulo"),
        rs.getString("descricao"),
        rs.getString("invalida_motivo"),
        rs.getString("proposta"),
        rs.getString("proposta_motivo"),
        rs.getString("decisao"),
        rs.getObject("lancamento_id", UUID.class),
        rs.getObject("categoria_id", UUID.class),
        rs.getString("resultado"),
        rs.getString("hash_externo"),
        rs.getObject("categoria_padrao", UUID.class));
  }
}
