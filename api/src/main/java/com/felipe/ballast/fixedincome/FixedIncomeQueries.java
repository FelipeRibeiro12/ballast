package com.felipe.ballast.fixedincome;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017). Toda consulta filtra por usuario_id; a FK composta da V14 garante que o
// título está numa conta investimento do mesmo usuário.
@Repository
class FixedIncomeQueries {

  // A última marcação de cada título vem junto, para a tela mostrar o que o banco disse por último.
  private static final String COLUMNS =
      """
      SELECT t.id, t.conta_id, t.tipo, t.nome, t.valor_aplicado, t.data_aplicacao, t.vencimento,
             t.indexador, t.taxa, t.data_resgate, t.valor_resgate, m.data AS marcacao_data,
             m.valor AS marcacao_valor
      FROM titulo_renda_fixa t
      LEFT JOIN LATERAL (
        SELECT data, valor FROM marcacao_renda_fixa
        WHERE titulo_id = t.id AND usuario_id = t.usuario_id
        ORDER BY data DESC LIMIT 1
      ) m ON true
      """;

  record Title(
      UUID id,
      UUID accountId,
      FixedIncomeType type,
      String name,
      BigDecimal invested,
      LocalDate applicationDate,
      LocalDate maturity,
      Indexer indexer,
      BigDecimal rate,
      LocalDate redemptionDate,
      BigDecimal redemptionValue,
      LocalDate lastMarkDate,
      BigDecimal lastMarkValue) {}

  record Mark(LocalDate date, BigDecimal value) {}

  record Account(boolean investment, boolean archived) {}

  // Campos que o usuário escreve no título. accountId nulo quando o texto não era UUID.
  record Fields(
      UUID accountId,
      FixedIncomeType type,
      String name,
      BigDecimal invested,
      LocalDate applicationDate,
      LocalDate maturity,
      Indexer indexer,
      BigDecimal rate) {}

  private final JdbcClient jdbc;

  FixedIncomeQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Vazio para conta alheia ou inexistente.
  Optional<Account> findAccount(UUID userId, UUID accountId) {
    return jdbc.sql(
            "SELECT tipo = 'investimento' AS investimento, arquivada FROM conta"
                + " WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", accountId)
        .query((rs, row) -> new Account(rs.getBoolean("investimento"), rs.getBoolean("arquivada")))
        .optional();
  }

  UUID insert(UUID userId, Fields fields) {
    UUID id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
    JdbcClient.StatementSpec spec =
        jdbc.sql(
                """
            INSERT INTO titulo_renda_fixa (id, usuario_id, conta_id, tipo, nome, valor_aplicado,
                                           data_aplicacao, vencimento, indexador, taxa)
            VALUES (:id, :userId, :accountId, :type, :name, :invested, :applicationDate,
                    :maturity, :indexer, :rate)
            """)
            .param("id", id)
            .param("userId", userId);
    bind(spec, fields).update();
    return id;
  }

  int update(UUID userId, UUID id, Fields fields) {
    JdbcClient.StatementSpec spec =
        jdbc.sql(
                """
            UPDATE titulo_renda_fixa
            SET conta_id = :accountId, tipo = :type, nome = :name, valor_aplicado = :invested,
                data_aplicacao = :applicationDate, vencimento = :maturity, indexador = :indexer,
                taxa = :rate
            WHERE usuario_id = :userId AND id = :id
            """)
            .param("id", id)
            .param("userId", userId);
    return bind(spec, fields).update();
  }

  int delete(UUID userId, UUID id) {
    return jdbc.sql("DELETE FROM titulo_renda_fixa WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  Optional<Title> find(UUID userId, UUID id) {
    return jdbc.sql(COLUMNS + " WHERE t.usuario_id = :userId AND t.id = :id")
        .param("userId", userId)
        .param("id", id)
        .query(FixedIncomeQueries::map)
        .optional();
  }

  // Só os das contas ativas, como as posições de cripto.
  List<Title> list(UUID userId) {
    return jdbc.sql(
            COLUMNS
                + """
                JOIN conta c ON c.id = t.conta_id AND c.usuario_id = t.usuario_id
                WHERE t.usuario_id = :userId AND NOT c.arquivada
                ORDER BY t.data_aplicacao, lower(t.nome), t.id
                """)
        .param("userId", userId)
        .query(FixedIncomeQueries::map)
        .list();
  }

  // Uma por dia: a segunda do mesmo dia corrige a primeira.
  void upsertMark(UUID userId, UUID titleId, LocalDate date, BigDecimal value) {
    jdbc.sql(
            """
            INSERT INTO marcacao_renda_fixa (usuario_id, titulo_id, data, valor)
            VALUES (:userId, :titleId, :date, :value)
            ON CONFLICT (titulo_id, data) DO UPDATE SET valor = EXCLUDED.valor
            """)
        .param("userId", userId)
        .param("titleId", titleId)
        .param("date", date)
        .param("value", value)
        .update();
  }

  void redeem(UUID userId, UUID id, LocalDate date, BigDecimal value) {
    jdbc.sql(
            "UPDATE titulo_renda_fixa SET data_resgate = :date, valor_resgate = :value"
                + " WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .param("date", date)
        .param("value", value)
        .update();
  }

  // A última marcação de cada título até a data, inclusive.
  Map<UUID, Mark> marksUpTo(UUID userId, LocalDate date) {
    Map<UUID, Mark> marks = new HashMap<>();
    jdbc.sql(
            """
            SELECT DISTINCT ON (titulo_id) titulo_id, data, valor
            FROM marcacao_renda_fixa
            WHERE usuario_id = :userId AND data <= :date
            ORDER BY titulo_id, data DESC
            """)
        .param("userId", userId)
        .param("date", date)
        .query(
            (rs, row) ->
                marks.put(
                    rs.getObject("titulo_id", UUID.class),
                    new Mark(rs.getObject("data", LocalDate.class), rs.getBigDecimal("valor"))))
        .list();
    return marks;
  }

  // Vencimento nulo vai com tipo, senão o Postgres não sabe o tipo do parâmetro.
  private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Fields fields) {
    return spec.param("accountId", fields.accountId())
        .param("type", fields.type().value())
        .param("name", fields.name())
        .param("invested", fields.invested())
        .param("applicationDate", fields.applicationDate())
        .param("maturity", fields.maturity(), Types.DATE)
        .param("indexer", fields.indexer().value())
        .param("rate", fields.rate());
  }

  private static Title map(ResultSet rs, int row) throws SQLException {
    return new Title(
        rs.getObject("id", UUID.class),
        rs.getObject("conta_id", UUID.class),
        FixedIncomeType.fromValue(rs.getString("tipo")),
        rs.getString("nome"),
        rs.getBigDecimal("valor_aplicado"),
        rs.getObject("data_aplicacao", LocalDate.class),
        rs.getObject("vencimento", LocalDate.class),
        Indexer.fromValue(rs.getString("indexador")),
        rs.getBigDecimal("taxa"),
        rs.getObject("data_resgate", LocalDate.class),
        rs.getBigDecimal("valor_resgate"),
        rs.getObject("marcacao_data", LocalDate.class),
        rs.getBigDecimal("marcacao_valor"));
  }
}
