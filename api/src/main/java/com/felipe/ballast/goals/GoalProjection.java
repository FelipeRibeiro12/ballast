package com.felipe.ballast.goals;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

// Quanto falta e quando chega (ADR-045). Os meses contam o atual: com alvo em dezembro, de outubro
// são três. Valores cheios; quem mostra arredonda para centavos.
record GoalProjection(
    BigDecimal remaining,
    boolean reached,
    boolean overdue,
    BigDecimal requiredMonthly,
    YearMonth arrivalByPlan,
    BigDecimal monthlyPace,
    YearMonth arrivalByPace) {

  private static final YearMonth LAST_MONTH = YearMonth.of(2199, 12);

  // Ritmo é a média dos três meses fechados antes do atual.
  private static final BigDecimal PACE_MONTHS = new BigDecimal("3");

  static GoalProjection of(
      BigDecimal target,
      BigDecimal saved,
      LocalDate targetDate,
      BigDecimal monthlyPlan,
      BigDecimal lastThreeMonths,
      LocalDate today) {
    BigDecimal remaining = target.subtract(saved).max(BigDecimal.ZERO);
    boolean reached = remaining.signum() == 0;
    boolean overdue = !reached && targetDate != null && targetDate.isBefore(today);
    YearMonth current = YearMonth.from(today);
    BigDecimal pace = lastThreeMonths.divide(PACE_MONTHS, MathContext.DECIMAL128);
    if (reached) {
      return new GoalProjection(remaining, true, false, null, null, pace, null);
    }
    BigDecimal required = null;
    if (targetDate != null && !overdue) {
      long months = ChronoUnit.MONTHS.between(current, YearMonth.from(targetDate)) + 1;
      // Para cima: arredondar para baixo deixaria a soma dos aportes um centavo abaixo do alvo.
      required = remaining.divide(BigDecimal.valueOf(months), 2, RoundingMode.CEILING);
    }
    return new GoalProjection(
        remaining,
        false,
        overdue,
        required,
        arrival(remaining, monthlyPlan, current),
        pace,
        arrival(remaining, pace, current));
  }

  // Nulo sem ritmo positivo ou quando passaria de 2199, o último ano que as datas da API aceitam.
  private static YearMonth arrival(BigDecimal remaining, BigDecimal perMonth, YearMonth current) {
    if (perMonth == null || perMonth.signum() <= 0) {
      return null;
    }
    BigDecimal months = remaining.divide(perMonth, 0, RoundingMode.CEILING);
    long monthsLeft = ChronoUnit.MONTHS.between(current, LAST_MONTH);
    if (months.compareTo(BigDecimal.valueOf(monthsLeft + 1)) > 0) {
      return null;
    }
    return current.plusMonths(months.longValueExact() - 1);
  }
}
