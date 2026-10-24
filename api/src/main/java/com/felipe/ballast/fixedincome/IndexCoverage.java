package com.felipe.ballast.fixedincome;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.NavigableMap;

// Diz, em uma frase curta, quando o índice guardado não cobre o período do título (ADR-058). O
// cálculo segue com o que há; o aviso só impede de confiar num valor que saiu menor.
final class IndexCoverage {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/uuuu");
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/uuuu");

  // Folga do CDI: fim de semana mais um feriado prolongado. O de hoje só sai amanhã.
  private static final int CDI_START_SLACK_DAYS = 4;
  private static final int CDI_END_SLACK_DAYS = 5;

  // O IPCA de um mês sai em meados do seguinte, então dois meses de atraso ainda é normal.
  private static final int IPCA_LAG_MONTHS = 2;

  private IndexCoverage() {}

  // Nulo quando cobre, ou quando o título não rende no período (aplicado no dia, resgatado).
  static String warning(
      FixedIncomeQueries.Title title,
      FixedIncomeService.Period period,
      NavigableMap<LocalDate, BigDecimal> cdi,
      NavigableMap<LocalDate, BigDecimal> ipca) {
    boolean redeemed =
        title.redemptionDate() != null && !period.end().isBefore(title.redemptionDate());
    if (redeemed || !period.end().isAfter(period.start())) {
      return null;
    }
    // Os três indexadores contam dia útil pelo CDI.
    if (cdi.isEmpty()) {
      return "sem CDI guardado";
    }
    if (cdi.firstKey().isAfter(period.start().plusDays(CDI_START_SLACK_DAYS))) {
      return "CDI guardado só a partir de " + cdi.firstKey().format(DAY);
    }
    if (cdi.lastKey().isBefore(period.end().minusDays(CDI_END_SLACK_DAYS))) {
      return "CDI guardado só até " + cdi.lastKey().format(DAY);
    }
    if (title.indexer() != Indexer.IPCA) {
      return null;
    }
    if (ipca.isEmpty()) {
      return "sem IPCA guardado";
    }
    if (YearMonth.from(ipca.firstKey()).isAfter(YearMonth.from(period.start()))) {
      return "IPCA guardado só a partir de " + ipca.firstKey().format(MONTH);
    }
    if (YearMonth.from(ipca.lastKey())
        .isBefore(YearMonth.from(period.end()).minusMonths(IPCA_LAG_MONTHS))) {
      return "IPCA guardado só até " + ipca.lastKey().format(MONTH);
    }
    return null;
  }
}
