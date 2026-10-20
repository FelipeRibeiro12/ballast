package com.felipe.ballast.recurrences;

import com.felipe.ballast.entries.EntryType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017). Toda consulta filtra por usuario_id, com uma exceção deliberada:
// listActiveTemplates, que é do job e anda pelos templates de todos os usuários. A escrita da
// materialização sempre amarra o template ao usuario_id dele.
// Os ids vêm da aplicação (UUID v7), como nas outras tabelas.
@Repository
class RecurrenceQueries {

  // Linhas por INSERT: cada data usa 2 parâmetros, e uma série diária de 12 meses cabe em uma ida.
  private static final int INSERT_CHUNK = 400;

  private static final String INSERT_HEAD =
      """
      INSERT INTO lancamento (id, usuario_id, tipo, titulo, descricao, valor, categoria_id,
                              conta_id, data_competencia, data_caixa, status, template_id,
                              ocorrencia_data, desgarrado)
      SELECT o.id, t.usuario_id, t.tipo, t.titulo, t.descricao, t.valor_total, t.categoria_id,
             t.conta_id, o.dia, o.dia, 'previsto', t.id, o.dia, false
      FROM template_lancamento t
      CROSS JOIN (VALUES """;

  private static final String INSERT_TAIL =
      """
      ) AS o(id, dia)
      WHERE t.id = :templateId AND t.usuario_id = :userId
      ON CONFLICT (template_id, ocorrencia_data) DO NOTHING
      """;

  private static final String COLUMNS =
      """
      SELECT t.id, t.tipo, t.titulo, t.descricao, t.valor_total, t.categoria_id, t.conta_id,
             t.ativo, t.criado_em,
             r.frequencia, r.intervalo, r.dia_do_mes, r.dia_da_semana, r.inicio, r.fim,
             r.ocorrencias_max,
             (SELECT min(l.ocorrencia_data) FROM lancamento l
               WHERE l.usuario_id = t.usuario_id AND l.template_id = t.id
                 AND l.status = 'previsto' AND l.ocorrencia_data >= :today) AS proxima
      FROM template_lancamento t
      JOIN regra_recorrencia r ON r.id = t.regra_id AND r.usuario_id = t.usuario_id
      """;

  record LockedTemplate(boolean active, UUID accountId, RecurrenceRule rule) {}

  record TemplateRef(UUID userId, UUID templateId) {}

  private final JdbcClient jdbc;

  RecurrenceQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<RecurrenceSummary> find(UUID userId, UUID id, LocalDate today) {
    return jdbc.sql(COLUMNS + "WHERE t.usuario_id = :userId AND t.id = :id")
        .param("today", today)
        .param("userId", userId)
        .param("id", id)
        .query(RecurrenceQueries::map)
        .optional();
  }

  // Ativas e inativas. lower() para a ordem não separar maiúscula de minúscula; o id desempata.
  List<RecurrenceSummary> list(UUID userId, LocalDate today) {
    return jdbc.sql(COLUMNS + "WHERE t.usuario_id = :userId ORDER BY lower(t.titulo), t.id")
        .param("today", today)
        .param("userId", userId)
        .query(RecurrenceQueries::map)
        .list();
  }

  // Trava a linha da conta até o fim da transação. Vazio quando a conta não existe ou é de outro
  // usuário; senão, se está arquivada. Quem arquiva faz UPDATE nesta linha e espera na fila, então
  // nenhuma ocorrência nova entra depois de o arquivar responder 200.
  Optional<Boolean> lockAccount(UUID userId, UUID accountId) {
    return jdbc.sql(
            "SELECT arquivada FROM conta WHERE usuario_id = :userId AND id = :id FOR UPDATE")
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

  UUID insertRule(UUID userId, RecurrenceRule rule) {
    UUID id = newId();
    jdbc.sql(
            """
            INSERT INTO regra_recorrencia (id, usuario_id, frequencia, intervalo, dia_do_mes,
                                           dia_da_semana, inicio, fim, ocorrencias_max)
            VALUES (:id, :userId, :frequency, :interval, :dayOfMonth, :dayOfWeek, :start, :end,
                    :maxOccurrences)
            """)
        .param("id", id)
        .param("userId", userId)
        .param("frequency", rule.frequency().value())
        .param("interval", rule.interval())
        .param("dayOfMonth", rule.dayOfMonth(), java.sql.Types.SMALLINT)
        .param("dayOfWeek", rule.dayOfWeek(), java.sql.Types.SMALLINT)
        .param("start", rule.start())
        .param("end", rule.end(), java.sql.Types.DATE)
        .param("maxOccurrences", rule.maxOccurrences(), java.sql.Types.INTEGER)
        .update();
    return id;
  }

  UUID insertTemplate(UUID userId, RecurrenceInput input, UUID ruleId) {
    UUID id = newId();
    jdbc.sql(
            """
            INSERT INTO template_lancamento (id, usuario_id, tipo, titulo, descricao, categoria_id,
                                             conta_id, valor_total, regra_id)
            VALUES (:id, :userId, :type, :title, :description, :categoryId, :accountId, :amount,
                    :ruleId)
            """)
        .param("id", id)
        .param("userId", userId)
        .param("type", input.type().value())
        .param("title", input.title())
        .param("description", input.description(), java.sql.Types.VARCHAR)
        .param("categoryId", input.categoryId())
        .param("accountId", input.accountId())
        .param("amount", input.amount())
        .param("ruleId", ruleId)
        .update();
    return id;
  }

  // Trava o template e devolve o estado atual com a regra. Vazio para template alheio ou
  // inexistente. É a trava que serializa desativar, criar e o job: quem chega depois enxerga o
  // resultado de quem chegou antes.
  Optional<LockedTemplate> lockTemplate(UUID userId, UUID templateId) {
    return jdbc.sql(
            """
            SELECT t.ativo, t.conta_id,
                   r.frequencia, r.intervalo, r.dia_do_mes, r.dia_da_semana, r.inicio, r.fim,
                   r.ocorrencias_max
            FROM template_lancamento t
            JOIN regra_recorrencia r ON r.id = t.regra_id AND r.usuario_id = t.usuario_id
            WHERE t.usuario_id = :userId AND t.id = :id
            FOR UPDATE OF t
            """)
        .param("userId", userId)
        .param("id", templateId)
        .query(
            (rs, row) ->
                new LockedTemplate(
                    rs.getBoolean("ativo"), rs.getObject("conta_id", UUID.class), mapRule(rs)))
        .optional();
  }

  // Grava as ocorrências copiando os campos do próprio template, na mesma instrução: o valor, o
  // título e as FKs nunca divergem do template. ON CONFLICT pula a data que já existe (gerada
  // antes, editada ou cancelada), que é o que torna a rodada repetível. Devolve quantas entraram.
  int insertOccurrences(UUID userId, UUID templateId, List<LocalDate> dates) {
    int inserted = 0;
    for (int from = 0; from < dates.size(); from += INSERT_CHUNK) {
      List<LocalDate> chunk = dates.subList(from, Math.min(from + INSERT_CHUNK, dates.size()));
      // Só os nomes dos parâmetros são montados aqui (id0, d0, ...); os valores vão como
      // parâmetros, nunca no texto do SQL.
      StringBuilder values = new StringBuilder();
      Map<String, Object> params = new HashMap<>();
      for (int i = 0; i < chunk.size(); i++) {
        values
            .append(i == 0 ? "" : ", ")
            .append("(CAST(:id")
            .append(i)
            .append(" AS uuid), CAST(:d")
            .append(i)
            .append(" AS date))");
        params.put("id" + i, newId());
        params.put("d" + i, chunk.get(i));
      }
      inserted +=
          jdbc.sql(INSERT_HEAD + values + INSERT_TAIL)
              .param("templateId", templateId)
              .param("userId", userId)
              .params(params)
              .update();
    }
    return inserted;
  }

  void deactivate(UUID userId, UUID templateId) {
    jdbc.sql("UPDATE template_lancamento SET ativo = false WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", templateId)
        .update();
  }

  // Só previsto, não desgarrado e de hoje em diante: o que já passou fica, e a ocorrência editada
  // ou confirmada é do usuário.
  int deleteFuturePlanned(UUID userId, UUID templateId, LocalDate today) {
    return jdbc.sql(
            """
            DELETE FROM lancamento
            WHERE usuario_id = :userId AND template_id = :id AND status = 'previsto'
              AND NOT desgarrado AND ocorrencia_data >= :today
            """)
        .param("userId", userId)
        .param("id", templateId)
        .param("today", today)
        .update();
  }

  // Lote do job, pela chave primária: anda sem OFFSET e não perde nem repete template quando
  // outro entra ou sai no meio da rodada.
  List<TemplateRef> listActiveTemplates(UUID afterId, int limit) {
    return jdbc.sql(
            "SELECT usuario_id, id FROM template_lancamento WHERE ativo AND id > :after"
                + " ORDER BY id LIMIT :limit")
        .param("after", afterId)
        .param("limit", limit)
        .query(
            (rs, row) ->
                new TemplateRef(
                    rs.getObject("usuario_id", UUID.class), rs.getObject("id", UUID.class)))
        .list();
  }

  private static UUID newId() {
    // O gerador do Hibernate não usa a sessão; é o mesmo dos @UuidGenerator das entidades.
    return UuidVersion7Strategy.INSTANCE.generateUuid(null);
  }

  private static RecurrenceRule mapRule(ResultSet rs) throws SQLException {
    return new RecurrenceRule(
        RecurrenceFrequency.fromValue(rs.getString("frequencia")),
        rs.getInt("intervalo"),
        rs.getObject("dia_do_mes", Integer.class),
        rs.getObject("dia_da_semana", Integer.class),
        rs.getObject("inicio", LocalDate.class),
        rs.getObject("fim", LocalDate.class),
        rs.getObject("ocorrencias_max", Integer.class));
  }

  private static RecurrenceSummary map(ResultSet rs, int row) throws SQLException {
    BigDecimal amount = rs.getBigDecimal("valor_total");
    return new RecurrenceSummary(
        rs.getObject("id", UUID.class),
        entryType(rs.getString("tipo")),
        rs.getString("titulo"),
        rs.getString("descricao"),
        amount,
        rs.getObject("categoria_id", UUID.class),
        rs.getObject("conta_id", UUID.class),
        rs.getBoolean("ativo"),
        rs.getObject("criado_em", OffsetDateTime.class).toInstant(),
        mapRule(rs),
        rs.getObject("proxima", LocalDate.class));
  }

  private static EntryType entryType(String value) {
    return switch (value) {
      case "receita" -> EntryType.INCOME;
      case "despesa" -> EntryType.EXPENSE;
      default -> throw new IllegalStateException("tipo de template desconhecido no banco");
    };
  }
}
