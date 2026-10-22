package com.felipe.ballast.recurrences;

import com.felipe.ballast.entries.CardBilling;
import com.felipe.ballast.entries.EntryType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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

  // Linhas por INSERT: cada data usa até 5 parâmetros, e uma série diária de 12 meses cabe em uma
  // ida.
  private static final int INSERT_CHUNK = 250;

  private static final String INSERT_HEAD =
      """
      INSERT INTO lancamento (id, usuario_id, tipo, titulo, descricao, valor, categoria_id,
                              conta_id, data_competencia, data_caixa, status, template_id,
                              ocorrencia_data, desgarrado, parcela_ordem)
      SELECT o.id, t.usuario_id, t.tipo, t.titulo, t.descricao, COALESCE(o.valor, t.valor_total),
             t.categoria_id, t.conta_id, o.dia, o.caixa, 'previsto', t.id, o.dia, false, o.ordem
      FROM template_lancamento t
      CROSS JOIN (VALUES """;

  private static final String INSERT_TAIL =
      """
      ) AS o(id, dia, caixa, ordem, valor)
      WHERE t.id = :templateId AND t.usuario_id = :userId
      ON CONFLICT (template_id, ocorrencia_data, parcela_ordem)
      WHERE template_id IS NOT NULL DO NOTHING
      """;

  private static final String COLUMNS =
      """
      SELECT t.id, t.tipo, t.titulo, t.descricao, t.valor_total, t.categoria_id, t.conta_id,
             t.ativo, t.criado_em,
             r.frequencia, r.intervalo, r.dia_do_mes, r.dia_da_semana, r.inicio, r.fim,
             r.ocorrencias_max,
             (SELECT min(l.data_caixa) FROM lancamento l
               WHERE t.ativo AND l.usuario_id = t.usuario_id AND l.template_id = t.id
                 AND l.status = 'previsto' AND l.data_caixa >= :today) AS proxima
      FROM template_lancamento t
      JOIN regra_recorrencia r ON r.id = t.regra_id AND r.usuario_id = t.usuario_id
      """;

  record LockedTemplate(
      boolean active, UUID accountId, BigDecimal amount, RecurrenceRule rule, Division division) {}

  // Ocorrência a gravar. date é a data gerada pela regra (chave e competência); cashDate é a caixa,
  // igual a ela fora de cartão. Sem divisão, parcelOrder e amount são nulos e o INSERT usa o valor
  // do template.
  record NewOccurrence(
      LocalDate date, LocalDate cashDate, Integer parcelOrder, BigDecimal amount) {}

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
        .optional()
        .map(summary -> summary.withDivision(findDivision(userId, id).orElse(null)));
  }

  // Ativas e inativas. lower() para a ordem não separar maiúscula de minúscula; o id desempata.
  List<RecurrenceSummary> list(UUID userId, LocalDate today) {
    List<RecurrenceSummary> summaries =
        jdbc.sql(COLUMNS + "WHERE t.usuario_id = :userId ORDER BY lower(t.titulo), t.id")
            .param("today", today)
            .param("userId", userId)
            .query(RecurrenceQueries::map)
            .list();
    // Uma ida só para as divisões do usuário, em vez de uma por template.
    Map<UUID, Division> divisions = divisionsOf(userId, null);
    return summaries.stream().map(s -> s.withDivision(divisions.get(s.id()))).toList();
  }

  Optional<Division> findDivision(UUID userId, UUID templateId) {
    return Optional.ofNullable(divisionsOf(userId, templateId).get(templateId));
  }

  // templateId nulo traz as divisões de todos os templates do usuário. Modo é o que a linha
  // guarda: percentual preenchido é percentual, senão valor fixo (o CHECK garante um dos dois).
  private Map<UUID, Division> divisionsOf(UUID userId, UUID templateId) {
    Map<UUID, List<DivisionPart>> parts = new HashMap<>();
    Map<UUID, DivisionMode> modes = new HashMap<>();
    jdbc.sql(
            """
            SELECT template_id, ordem, dia_do_mes, percentual, valor_fixo
            FROM template_divisao
            WHERE usuario_id = :userId AND (CAST(:templateId AS uuid) IS NULL
                                            OR template_id = CAST(:templateId AS uuid))
            ORDER BY template_id, ordem
            """)
        .param("userId", userId)
        .param("templateId", templateId, java.sql.Types.OTHER)
        .query(
            rs -> {
              UUID id = rs.getObject("template_id", UUID.class);
              BigDecimal percent = rs.getBigDecimal("percentual");
              modes.put(id, percent == null ? DivisionMode.AMOUNT : DivisionMode.PERCENT);
              parts
                  .computeIfAbsent(id, key -> new ArrayList<>())
                  .add(
                      new DivisionPart(
                          rs.getInt("ordem"),
                          rs.getInt("dia_do_mes"),
                          percent,
                          rs.getBigDecimal("valor_fixo")));
            });
    Map<UUID, Division> result = new HashMap<>();
    parts.forEach((id, list) -> result.put(id, new Division(modes.get(id), list)));
    return result;
  }

  void insertDivision(UUID userId, UUID templateId, Division division) {
    for (DivisionPart part : division.parts()) {
      jdbc.sql(
              """
              INSERT INTO template_divisao (id, usuario_id, template_id, dia_do_mes, percentual,
                                            valor_fixo, ordem)
              VALUES (:id, :userId, :templateId, :day, :percent, :fixedAmount, :order)
              """)
          .param("id", newId())
          .param("userId", userId)
          .param("templateId", templateId)
          .param("day", part.dayOfMonth())
          .param("percent", part.percent(), java.sql.Types.NUMERIC)
          .param("fixedAmount", part.fixedAmount(), java.sql.Types.NUMERIC)
          .param("order", part.order())
          .update();
    }
  }

  // Trava a linha da conta até o fim da transação. Vazio quando a conta não existe ou é de outro
  // usuário; senão, se está arquivada. Quem arquiva faz UPDATE nesta linha e espera na fila, então
  // nenhuma ocorrência nova entra depois de o arquivar responder 200.
  // Fechamento e vencimento do cartão da conta; vazio quando a conta não é de cartão. Os dois dias
  // não mudam depois que o cartão nasce (ADR-031), então o job lê sem travar a conta.
  Optional<CardBilling> findCardBilling(UUID userId, UUID accountId) {
    return jdbc.sql(
            "SELECT dia_fechamento, dia_vencimento FROM cartao"
                + " WHERE usuario_id = :userId AND conta_id = :id")
        .param("userId", userId)
        .param("id", accountId)
        .query(
            (rs, row) -> new CardBilling(rs.getInt("dia_fechamento"), rs.getInt("dia_vencimento")))
        .optional();
  }

  Optional<Boolean> lockAccount(UUID userId, UUID accountId) {
    return jdbc.sql(
            "SELECT arquivada FROM conta WHERE usuario_id = :userId AND id = :id FOR UPDATE")
        .param("userId", userId)
        .param("id", accountId)
        .query(Boolean.class)
        .optional();
  }

  // Conta cripto não recebe lançamento, nem por template (ADR-035).
  boolean isCryptoAccount(UUID userId, UUID accountId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM conta WHERE usuario_id = :userId AND id = :id"
                + " AND tipo = 'cripto')")
        .param("userId", userId)
        .param("id", accountId)
        .query(Boolean.class)
        .single();
  }

  // Lê "arquivada" sem travar a conta: o job não pode segurar a conta enquanto espera linha de
  // lançamento, que é a ordem inversa de PUT e confirmar. Vazio quando a conta não existe.
  Optional<Boolean> isAccountArchived(UUID userId, UUID accountId) {
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

  // Regra do ADR-034 para despesa: conta sem lista (qualquer tipo que não seja benefício, ou
  // benefício com lista vazia) aceita tudo. Roda com a conta travada na criação.
  boolean isCategoryPermitted(UUID userId, UUID accountId, UUID categoryId) {
    return jdbc.sql(
            """
            SELECT NOT EXISTS (SELECT 1 FROM conta_categoria_permitida
                               WHERE usuario_id = :userId AND conta_id = :accountId)
                   OR EXISTS (SELECT 1 FROM conta_categoria_permitida
                              WHERE usuario_id = :userId AND conta_id = :accountId
                                AND categoria_id = :categoryId)
            """)
        .param("userId", userId)
        .param("accountId", accountId)
        .param("categoryId", categoryId)
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
            SELECT t.ativo, t.conta_id, t.valor_total,
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
                    rs.getBoolean("ativo"),
                    rs.getObject("conta_id", UUID.class),
                    rs.getBigDecimal("valor_total"),
                    mapRule(rs),
                    null))
        .optional()
        .map(
            locked ->
                new LockedTemplate(
                    locked.active(),
                    locked.accountId(),
                    locked.amount(),
                    locked.rule(),
                    findDivision(userId, templateId).orElse(null)));
  }

  // Grava as ocorrências copiando os campos do próprio template, na mesma instrução: o título e as
  // FKs nunca divergem do template. O valor vem do template, ou da parcela quando há divisão.
  // ON CONFLICT pula a ocorrência que já existe (gerada antes, editada ou cancelada), que é o que
  // torna a rodada repetível. A chave inclui a ordem da parcela (ADR-027). Devolve quantas
  // entraram.
  int insertOccurrences(UUID userId, UUID templateId, List<NewOccurrence> occurrences) {
    int inserted = 0;
    for (int from = 0; from < occurrences.size(); from += INSERT_CHUNK) {
      List<NewOccurrence> chunk =
          occurrences.subList(from, Math.min(from + INSERT_CHUNK, occurrences.size()));
      // Só os nomes dos parâmetros são montados aqui (id0, d0, ...); os valores vão como
      // parâmetros, nunca no texto do SQL. Ordem e valor nulos entram como NULL com tipo.
      StringBuilder values = new StringBuilder();
      Map<String, Object> params = new HashMap<>();
      for (int i = 0; i < chunk.size(); i++) {
        NewOccurrence occurrence = chunk.get(i);
        values
            .append(i == 0 ? "" : ", ")
            .append("(CAST(:id")
            .append(i)
            .append(" AS uuid), CAST(:d")
            .append(i)
            .append(" AS date), CAST(:c")
            .append(i)
            .append(" AS date), ");
        params.put("id" + i, newId());
        params.put("d" + i, occurrence.date());
        params.put("c" + i, occurrence.cashDate());
        if (occurrence.parcelOrder() == null) {
          values.append("CAST(NULL AS smallint), CAST(NULL AS numeric))");
        } else {
          values.append("CAST(:o").append(i).append(" AS smallint), CAST(:v").append(i);
          values.append(" AS numeric))");
          params.put("o" + i, occurrence.parcelOrder());
          params.put("v" + i, occurrence.amount());
        }
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

  Optional<EntryType> templateType(UUID userId, UUID templateId) {
    return jdbc.sql("SELECT tipo FROM template_lancamento WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", templateId)
        .query((rs, row) -> entryType(rs.getString("tipo")))
        .optional();
  }

  // O tipo não muda (ADR-036): categoria e sinal dependem dele.
  void updateTemplate(UUID userId, UUID templateId, RecurrenceInput input) {
    jdbc.sql(
            """
            UPDATE template_lancamento SET titulo = :title, descricao = :description,
                   categoria_id = :categoryId, conta_id = :accountId, valor_total = :amount
            WHERE usuario_id = :userId AND id = :id
            """)
        .param("userId", userId)
        .param("id", templateId)
        .param("title", input.title())
        .param("description", input.description(), java.sql.Types.VARCHAR)
        .param("categoryId", input.categoryId())
        .param("accountId", input.accountId())
        .param("amount", input.amount())
        .update();
  }

  void updateRule(UUID userId, UUID templateId, RecurrenceRule rule) {
    jdbc.sql(
            """
            UPDATE regra_recorrencia SET frequencia = :frequency, intervalo = :interval,
                   dia_do_mes = :dayOfMonth, dia_da_semana = :dayOfWeek, inicio = :start,
                   fim = :end, ocorrencias_max = :maxOccurrences
            WHERE usuario_id = :userId
              AND id = (SELECT regra_id FROM template_lancamento
                        WHERE usuario_id = :userId AND id = :id)
            """)
        .param("userId", userId)
        .param("id", templateId)
        .param("frequency", rule.frequency().value())
        .param("interval", rule.interval())
        .param("dayOfMonth", rule.dayOfMonth(), java.sql.Types.SMALLINT)
        .param("dayOfWeek", rule.dayOfWeek(), java.sql.Types.SMALLINT)
        .param("start", rule.start())
        .param("end", rule.end(), java.sql.Types.DATE)
        .param("maxOccurrences", rule.maxOccurrences(), java.sql.Types.INTEGER)
        .update();
  }

  void deleteDivision(UUID userId, UUID templateId) {
    jdbc.sql("DELETE FROM template_divisao WHERE usuario_id = :userId AND template_id = :id")
        .param("userId", userId)
        .param("id", templateId)
        .update();
  }

  void activate(UUID userId, UUID templateId) {
    jdbc.sql("UPDATE template_lancamento SET ativo = true WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", templateId)
        .update();
  }

  // Todas as previstas, inclusive vencidas e editadas: sem o template elas não teriam dono.
  // Realizada e cancelada ficam como lançamento avulso; parcela_ordem só existe com template (V9).
  void deleteTemplateKeepingHistory(UUID userId, UUID templateId) {
    jdbc.sql(
            "DELETE FROM lancamento WHERE usuario_id = :userId AND template_id = :id"
                + " AND status = 'previsto'")
        .param("userId", userId)
        .param("id", templateId)
        .update();
    jdbc.sql(
            """
            UPDATE lancamento SET template_id = NULL, ocorrencia_data = NULL,
                                  parcela_ordem = NULL, desgarrado = false
            WHERE usuario_id = :userId AND template_id = :id
            """)
        .param("userId", userId)
        .param("id", templateId)
        .update();
    deleteDivision(userId, templateId);
    UUID ruleId =
        jdbc.sql(
                "DELETE FROM template_lancamento WHERE usuario_id = :userId AND id = :id"
                    + " RETURNING regra_id")
            .param("userId", userId)
            .param("id", templateId)
            .query(UUID.class)
            .single();
    jdbc.sql("DELETE FROM regra_recorrencia WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", ruleId)
        .update();
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
        rs.getObject("proxima", LocalDate.class),
        null);
  }

  private static EntryType entryType(String value) {
    return switch (value) {
      case "receita" -> EntryType.INCOME;
      case "despesa" -> EntryType.EXPENSE;
      default -> throw new IllegalStateException("tipo de template desconhecido no banco");
    };
  }
}
