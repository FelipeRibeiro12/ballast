package com.felipe.ballast.goals;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class GoalProjectionTest {

  private static final LocalDate TODAY = LocalDate.parse("2026-10-23");

  private static GoalProjection project(
      String target, String saved, String targetDate, String plan, String lastThreeMonths) {
    return GoalProjection.of(
        new BigDecimal(target),
        new BigDecimal(saved),
        targetDate == null ? null : LocalDate.parse(targetDate),
        plan == null ? null : new BigDecimal(plan),
        new BigDecimal(lastThreeMonths),
        TODAY);
  }

  @Test
  void whatIsLeftNeverGoesBelowZeroAndReachingCountsTheExactTarget() {
    assertThat(project("1000", "400", null, null, "0").remaining()).isEqualByComparingTo("600");
    assertThat(project("1000", "1000", null, null, "0").reached()).isTrue();
    assertThat(project("1000", "1200", null, null, "0").remaining()).isEqualByComparingTo("0");
    assertThat(project("1000", "999.99", null, null, "0").reached()).isFalse();
  }

  // De outubro a dezembro são três meses, contando este. Arredonda para cima: três de 333,33
  // ficariam um centavo abaixo do alvo.
  @Test
  void theMonthlyAmountToReachTheDateRoundsUpSoItReallyGetsThere() {
    GoalProjection projection = project("1000", "0", "2026-12-31", null, "0");

    assertThat(projection.requiredMonthly()).isEqualByComparingTo("333.34");
    assertThat(projection.requiredMonthly().multiply(new BigDecimal("3")))
        .isGreaterThanOrEqualTo(new BigDecimal("1000"));
  }

  @Test
  void aTargetThisMonthAsksForEverythingNow() {
    assertThat(project("1000", "250", "2026-10-31", null, "0").requiredMonthly())
        .isEqualByComparingTo("750");
  }

  @Test
  void aPastTargetDateHasNoMonthlyAmountAndIsOverdue() {
    GoalProjection projection = project("1000", "250", "2026-09-30", null, "0");

    assertThat(projection.requiredMonthly()).isNull();
    assertThat(projection.overdue()).isTrue();
    assertThat(project("1000", "1000", "2026-09-30", null, "0").overdue()).isFalse();
  }

  // 600 faltando a 250 por mês: três aportes, o terceiro em dezembro contando outubro.
  @Test
  void thePlanSaysWhichMonthItGetsThere() {
    assertThat(project("1000", "400", null, "250", "0").arrivalByPlan())
        .isEqualTo(YearMonth.of(2026, 12));
    assertThat(project("1000", "900", null, "250", "0").arrivalByPlan())
        .isEqualTo(YearMonth.of(2026, 10));
  }

  // 300 nos três últimos meses fechados dá 100 por mês; faltam 600, seis meses contando outubro.
  @Test
  void thePaceOfTheLastThreeClosedMonthsSaysWhenItGetsThereToo() {
    GoalProjection projection = project("1000", "400", null, null, "300");

    assertThat(projection.monthlyPace()).isEqualByComparingTo("100");
    assertThat(projection.arrivalByPace()).isEqualTo(YearMonth.of(2027, 3));
  }

  @Test
  void withoutPositivePaceOrPlanThereIsNoArrival() {
    GoalProjection projection = project("1000", "400", null, null, "-50");

    assertThat(projection.arrivalByPace()).isNull();
    assertThat(projection.arrivalByPlan()).isNull();
  }

  @Test
  void aReachedGoalHasNoProjection() {
    GoalProjection projection = project("1000", "1000", "2026-12-31", "100", "300");

    assertThat(projection.requiredMonthly()).isNull();
    assertThat(projection.arrivalByPlan()).isNull();
    assertThat(projection.arrivalByPace()).isNull();
  }

  // Um centavo por mês para chegar a 90 trilhões passa de 2199: sem data em vez de estourar.
  @Test
  void anArrivalBeyondTheLastSupportedYearIsLeftEmpty() {
    assertThat(project("90071992547409.91", "0", null, "0.01", "0").arrivalByPlan()).isNull();
  }
}
