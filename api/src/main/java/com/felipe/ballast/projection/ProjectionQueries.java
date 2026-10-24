package com.felipe.ballast.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Saldo projetado em SQL (ADR-017, ADR-029), uma ida ao banco. Toda consulta filtra por usuario_id.
// O sinal do lançamento é o mesmo do saldo atual (AccountQueries): receita e perna de entrada
// somam, despesa e perna de saída subtraem. Sem coluna de saldo: nada é materializado.
@Repository
class ProjectionQueries {

  // Sem contaId, só as contas ativas que não são de benefício: R$ 800 de VR não pagam o aluguel
  // (ADR-034). Conta cripto também fica de fora: não tem saldo em reais nem lançamento (ADR-058).
  // Com contaId vale a conta pedida, arquivada ou não, benefício inclusive, e a contagem de contas
  // do escopo é como o serviço percebe conta alheia ou inexistente.
  private static final String ALL_ACTIVE =
      "NOT arquivada AND tipo <> 'beneficio' AND tipo <> 'cripto'";
  private static final String ONE_ACCOUNT = "id = :accountId";

  private static final String QUERY_START =
      """
      WITH scope AS (
        SELECT id, saldo_inicial, tipo FROM conta
        WHERE usuario_id = :userId AND\s
      """;

  // internal: a outra perna da transferência está numa conta do escopo. Esse movimento não é
  // entrada nem saída do total (o saldo já o anula); só ativa com arquivada, ou o filtro por
  // conta, deixa a perna aparecer.
  // MATERIALIZED lê os lançamentos do usuário uma vez para os totais e para o dia a dia. O
  // realizado vale em qualquer data (o saldo atual), por isso não há corte por data aqui.
  private static final String QUERY_END =
      """
      ),
      movement AS MATERIALIZED (
        SELECT l.status, l.data_caixa,
               (peer.id IS NOT NULL) AS internal,
               CASE l.tipo
                 WHEN 'receita' THEN l.valor
                 WHEN 'despesa' THEN -l.valor
                 WHEN 'transferencia' THEN
                   CASE WHEN te.id IS NOT NULL THEN l.valor ELSE -l.valor END
               END AS valor
        FROM lancamento l
        JOIN scope s ON s.id = l.conta_id
        LEFT JOIN transferencia te ON te.entrada_id = l.id AND te.usuario_id = l.usuario_id
        LEFT JOIN transferencia ts ON ts.saida_id = l.id AND ts.usuario_id = l.usuario_id
        LEFT JOIN scope peer ON peer.id = COALESCE(te.conta_saida_id, ts.conta_entrada_id)
        WHERE l.usuario_id = :userId AND l.status IN ('realizado', 'previsto')
      ),
      totals AS (
        SELECT COALESCE(SUM(valor) FILTER (WHERE data_caixa < :today), 0) AS before_today,
               COALESCE(SUM(valor) FILTER (WHERE status = 'previsto' AND data_caixa < :today), 0)
                 AS overdue,
               COALESCE(SUM(valor) FILTER (WHERE status = 'realizado'), 0) AS realized
        FROM movement
      ),
      daily AS (
        SELECT data_caixa,
               COALESCE(SUM(valor) FILTER (WHERE valor > 0 AND NOT internal), 0) AS inflow,
               COALESCE(-SUM(valor) FILTER (WHERE valor < 0 AND NOT internal), 0) AS outflow
        FROM movement
        WHERE data_caixa BETWEEN :today AND :today + :lastOffset
        GROUP BY data_caixa
      )
      SELECT g.day, COALESCE(d.inflow, 0) AS inflow, COALESCE(d.outflow, 0) AS outflow,
             t.before_today, t.overdue, t.realized,
             (SELECT COALESCE(SUM(saldo_inicial), 0) FROM scope) AS initial_balance,
             (SELECT COUNT(*) FROM scope) AS account_count,
             (SELECT COUNT(*) FROM scope WHERE tipo = 'cripto') AS crypto_count
      FROM (SELECT CAST(:today AS date) + n AS day FROM generate_series(0, :lastOffset) n) g
      LEFT JOIN daily d ON d.data_caixa = g.day
      CROSS JOIN totals t
      ORDER BY g.day
      """;

  private static final String ALL_QUERY = QUERY_START + ALL_ACTIVE + QUERY_END;
  private static final String ONE_QUERY = QUERY_START + ONE_ACCOUNT + QUERY_END;

  private final JdbcClient jdbc;

  ProjectionQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // accountId nulo é o total das contas ativas. A série tem sempre days pontos a partir de today.
  ProjectionRows load(UUID userId, UUID accountId, LocalDate today, int days) {
    JdbcClient.StatementSpec statement =
        jdbc.sql(accountId == null ? ALL_QUERY : ONE_QUERY)
            .param("userId", userId)
            .param("today", today)
            .param("lastOffset", days - 1);
    if (accountId != null) {
      statement = statement.param("accountId", accountId);
    }
    List<ProjectionRows.DayRow> rows = new ArrayList<>(days);
    // Os totais vêm repetidos em toda linha; guardo os da primeira.
    BigDecimal[] totals = new BigDecimal[4];
    long[] accountCount = new long[2];
    statement
        .query(
            (rs, row) -> {
              if (row == 0) {
                totals[0] = rs.getBigDecimal("initial_balance");
                totals[1] = rs.getBigDecimal("before_today");
                totals[2] = rs.getBigDecimal("overdue");
                totals[3] = rs.getBigDecimal("realized");
                accountCount[0] = rs.getLong("account_count");
                accountCount[1] = rs.getLong("crypto_count");
              }
              rows.add(
                  new ProjectionRows.DayRow(
                      rs.getObject("day", LocalDate.class),
                      rs.getBigDecimal("inflow"),
                      rs.getBigDecimal("outflow")));
              return null;
            })
        .list();
    return new ProjectionRows(
        accountCount[0], accountCount[1], totals[0], totals[1], totals[2], totals[3], rows);
  }
}
