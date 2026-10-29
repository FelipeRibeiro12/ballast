package com.felipe.ballast.budgets;

import com.felipe.ballast.entries.AggregationCompetence;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017). Toda consulta filtra por usuario_id; a FK composta da V18 garante que a
// categoria é de despesa e do mesmo usuário.
@Repository
class BudgetQueries {

  // Valor de lançamento tem 2 casas: as somas saem exatas, sem arredondar.
  record Consumption(
      UUID categoryId,
      String categoryName,
      BigDecimal ceiling,
      LocalDate since,
      BigDecimal spent,
      BigDecimal planned) {}

  private final JdbcClient jdbc;

  BudgetQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<String> categoryType(UUID userId, UUID categoryId) {
    return jdbc.sql("SELECT tipo FROM categoria WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", categoryId)
        .query(String.class)
        .optional();
  }

  // ceiling nulo encerra o orçamento a partir de since. A mesma categoria e mês troca o teto.
  void upsert(UUID userId, UUID categoryId, LocalDate since, BigDecimal ceiling) {
    jdbc.sql(
            """
            INSERT INTO orcamento (usuario_id, categoria_id, desde, teto)
            VALUES (:userId, :categoryId, :since, :ceiling)
            ON CONFLICT (usuario_id, categoria_id, desde) DO UPDATE SET teto = EXCLUDED.teto
            """)
        .param("userId", userId)
        .param("categoryId", categoryId)
        .param("since", since)
        .param("ceiling", ceiling)
        .update();
  }

  // O teto de cada categoria é a última linha com desde até o mês (ADR-043). O consumo soma a
  // categoria e as filhas dela por competência: gasto é o realizado até hoje, previsto é o que
  // ainda não aconteceu (recorrência sem confirmar e lançamento com data depois de hoje). Parcela
  // conta no mês dela e pelo valor dela (ADR-074): é gasto se o mês dela já começou, previsto se
  // é de mês futuro, porque o mês corrente não inclui as parcelas que ainda virão.
  List<Consumption> consumption(UUID userId, YearMonth month, LocalDate today) {
    return jdbc.sql(
            ("""
            WITH vigente AS (
              SELECT DISTINCT ON (categoria_id) categoria_id, teto, desde FROM orcamento
              WHERE usuario_id = :userId AND desde <= :start
              ORDER BY categoria_id, desde DESC
            ), alvo AS (
              SELECT v.categoria_id AS orcada, c.id AS categoria_id FROM vigente v
              JOIN categoria c ON c.usuario_id = :userId
                AND (c.id = v.categoria_id OR c.pai_id = v.categoria_id)
              WHERE v.teto IS NOT NULL
            ), consumo AS (
              SELECT a.orcada,
                SUM(l.valor) FILTER (
                  WHERE l.status = 'realizado'
                    AND (%2$s) <= CASE WHEN l.parcela_num IS NULL THEN CAST(:today AS date)
                      ELSE CAST(:todayMonth AS date) END) AS gasto,
                SUM(l.valor) FILTER (
                  WHERE l.status = 'previsto'
                    OR (l.status = 'realizado'
                      AND (%2$s) > CASE WHEN l.parcela_num IS NULL THEN CAST(:today AS date)
                        ELSE CAST(:todayMonth AS date) END)) AS previsto
              FROM alvo a
              JOIN lancamento l ON l.usuario_id = :userId AND l.categoria_id = a.categoria_id
                AND l.tipo = 'despesa'
                AND NOT EXISTS (SELECT 1 FROM terceiro_lancamento tl
                  WHERE tl.lancamento_id = l.id AND tl.usuario_id = l.usuario_id)
                AND %1$s
                AND (%2$s) >= :start AND (%2$s) < :end
              GROUP BY a.orcada
            )
            SELECT v.categoria_id, c.nome, v.teto, v.desde,
              COALESCE(k.gasto, 0) AS gasto, COALESCE(k.previsto, 0) AS previsto
            FROM vigente v
            JOIN categoria c ON c.id = v.categoria_id AND c.usuario_id = :userId
            LEFT JOIN consumo k ON k.orcada = v.categoria_id
            WHERE v.teto IS NOT NULL
            ORDER BY lower(c.nome), c.id
            """)
                .formatted(AggregationCompetence.INDEX_RANGE, AggregationCompetence.DATE))
        .param("userId", userId)
        .param("start", month.atDay(1))
        .param("end", month.plusMonths(1).atDay(1))
        .param("today", today)
        .param("todayMonth", today.withDayOfMonth(1))
        .query(
            (rs, row) ->
                new Consumption(
                    rs.getObject("categoria_id", UUID.class),
                    rs.getString("nome"),
                    rs.getBigDecimal("teto"),
                    rs.getObject("desde", LocalDate.class),
                    rs.getBigDecimal("gasto"),
                    rs.getBigDecimal("previsto")))
        .list();
  }
}
