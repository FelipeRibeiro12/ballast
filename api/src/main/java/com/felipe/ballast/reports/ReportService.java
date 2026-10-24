package com.felipe.ballast.reports;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
class ReportService {

  // "Hoje" e o mês corrente são os do usuário, como no lançamento.
  static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  // Seis colunas duplas cabem em 360px com barra de 16px (ADR-046).
  static final int MONTHS_IN_COMPARISON = 6;

  // 9007199254740991 centavos em reais. Comparado com compareTo: a escala não conta.
  private static final BigDecimal LIMIT = new BigDecimal("90071992547409.91");

  record Report(
      YearMonth month,
      BigDecimal totalExpense,
      List<ReportQueries.CategorySpending> categories,
      List<ReportQueries.MonthTotals> months) {}

  private final ReportQueries queries;
  private final Clock clock;

  ReportService(ReportQueries queries, Clock clock) {
    this.queries = queries;
    this.clock = clock;
  }

  YearMonth currentMonth() {
    return YearMonth.from(today());
  }

  // Vazio quando alguma soma sai da faixa do JSON.
  Optional<Report> monthly(UUID userId, YearMonth month) {
    LocalDate today = today();
    List<ReportQueries.CategorySpending> categories =
        queries.spendingByCategory(userId, month, today);
    BigDecimal total =
        categories.stream()
            .map(ReportQueries.CategorySpending::spent)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    YearMonth first = month.minusMonths(MONTHS_IN_COMPARISON - 1);
    Map<YearMonth, ReportQueries.MonthTotals> found =
        queries.totalsByMonth(userId, first, month, today).stream()
            .collect(Collectors.toMap(ReportQueries.MonthTotals::month, Function.identity()));
    List<ReportQueries.MonthTotals> months = new ArrayList<>();
    for (YearMonth m = first; !m.isAfter(month); m = m.plusMonths(1)) {
      months.add(
          found.getOrDefault(
              m, new ReportQueries.MonthTotals(m, BigDecimal.ZERO, BigDecimal.ZERO)));
    }

    boolean fits =
        fits(total)
            && categories.stream().allMatch(c -> fits(c.spent()))
            && months.stream().allMatch(m -> fits(m.income()) && fits(m.expense()));
    return fits ? Optional.of(new Report(month, total, categories, months)) : Optional.empty();
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZONE);
  }

  private static boolean fits(BigDecimal amount) {
    return amount.compareTo(LIMIT) <= 0;
  }
}
