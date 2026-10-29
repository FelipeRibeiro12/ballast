package com.felipe.ballast.reports;

import com.felipe.ballast.entries.AggregationCompetence;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017), sempre por usuario_id. Os dois gráficos leem por competência e só o
// realizado até hoje, a mesma regra do gasto do orçamento (ADR-043). Parcela conta no mês dela e
// pelo valor dela (ADR-074); como já foi comprada, entra mesmo em mês depois de hoje. Valor de
// lançamento tem 2 casas: as somas saem exatas.
@Repository
class ReportQueries {

  record CategorySpending(UUID categoryId, String categoryName, BigDecimal spent) {}

  record MonthTotals(YearMonth month, BigDecimal income, BigDecimal expense) {}

  private final JdbcClient jdbc;

  ReportQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // A filha soma na categoria de cima: a hierarquia tem um nível só (V6).
  List<CategorySpending> spendingByCategory(UUID userId, YearMonth month, LocalDate today) {
    return jdbc.sql(
            ("""
            WITH gasto AS (
              SELECT COALESCE(c.pai_id, c.id) AS raiz_id, SUM(l.valor) AS valor
              FROM lancamento l
              JOIN categoria c ON c.usuario_id = :userId AND c.id = l.categoria_id
              WHERE l.usuario_id = :userId AND l.tipo = 'despesa' AND l.status = 'realizado'
                AND NOT EXISTS (SELECT 1 FROM terceiro_lancamento tl
                  WHERE tl.lancamento_id = l.id AND tl.usuario_id = l.usuario_id)
                AND %1$s
                AND (%2$s) >= :start AND (%2$s) < :end
                AND (l.parcela_num IS NOT NULL OR l.data_competencia <= :today)
              GROUP BY 1
            )
            SELECT g.raiz_id, c.nome, g.valor FROM gasto g
            JOIN categoria c ON c.usuario_id = :userId AND c.id = g.raiz_id
            ORDER BY g.valor DESC, lower(c.nome), c.id
            """)
                .formatted(AggregationCompetence.INDEX_RANGE, AggregationCompetence.DATE))
        .param("userId", userId)
        .param("start", month.atDay(1))
        .param("end", month.plusMonths(1).atDay(1))
        .param("today", today)
        .query(
            (rs, row) ->
                new CategorySpending(
                    rs.getObject("raiz_id", UUID.class),
                    rs.getString("nome"),
                    rs.getBigDecimal("valor")))
        .list();
  }

  // Só os meses com lançamento; quem chama completa os vazios com zero.
  List<MonthTotals> totalsByMonth(UUID userId, YearMonth first, YearMonth last, LocalDate today) {
    return jdbc.sql(
            ("""
            SELECT date_trunc('month', (%2$s)::timestamp)::date AS mes,
              COALESCE(SUM(l.valor) FILTER (WHERE l.tipo = 'receita'), 0) AS receita,
              COALESCE(SUM(l.valor) FILTER (WHERE l.tipo = 'despesa'), 0) AS despesa
            FROM lancamento l
            WHERE l.usuario_id = :userId AND l.status = 'realizado'
              AND NOT EXISTS (SELECT 1 FROM terceiro_lancamento tl
                  WHERE tl.lancamento_id = l.id AND tl.usuario_id = l.usuario_id)
              AND %1$s
              AND (%2$s) >= :start AND (%2$s) < :end
              AND (l.parcela_num IS NOT NULL OR l.data_competencia <= :today)
            GROUP BY 1
            """)
                .formatted(AggregationCompetence.INDEX_RANGE, AggregationCompetence.DATE))
        .param("userId", userId)
        .param("start", first.atDay(1))
        .param("end", last.plusMonths(1).atDay(1))
        .param("today", today)
        .query(
            (rs, row) ->
                new MonthTotals(
                    YearMonth.from(rs.getObject("mes", LocalDate.class)),
                    rs.getBigDecimal("receita"),
                    rs.getBigDecimal("despesa")))
        .list();
  }
}
