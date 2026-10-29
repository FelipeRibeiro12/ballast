package com.felipe.ballast.entries;

// Trecho de SQL compartilhado por tudo que agrega por categoria usando competência (relatório,
// gráfico, orçamento, resumo do Início), para os números concordarem (ADR-075). O alias da tabela
// tem que ser "l".
public final class AggregationCompetence {

  // Lançamento comum devolve a própria data_competencia. Parcela devolve o dia 1 do mês da
  // data_competencia mais (parcela_num - 1) meses: a data gravada continua a da compra (ADR-032),
  // só a agregação desloca. O cast para timestamp evita o fuso da sessão no date_trunc.
  public static final String DATE =
      """
      CASE WHEN l.parcela_num IS NULL THEN l.data_competencia
        ELSE (date_trunc('month', l.data_competencia::timestamp)
          + make_interval(months => l.parcela_num - 1))::date END""";

  // Filtro que o índice (usuario_id, data_competencia, ...) da V18 aproveita. A competência
  // agregada nunca vem antes da gravada e anda no máximo 47 meses (parcela_total <= 48, V11):
  // limita a leitura sem mudar o resultado, que quem decide é o DATE.
  public static final String INDEX_RANGE =
      "l.data_competencia >= CAST(:start AS date) - INTERVAL '47 months'"
          + " AND l.data_competencia < CAST(:end AS date)";

  private AggregationCompetence() {}
}
