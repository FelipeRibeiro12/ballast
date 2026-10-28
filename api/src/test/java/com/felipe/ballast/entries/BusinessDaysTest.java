package com.felipe.ballast.entries;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BusinessDaysTest {

  @Test
  void keepsAWeekdayThatIsNotAHoliday() {
    assertThat(BusinessDays.nextOnOrAfter(LocalDate.of(2026, 10, 27)))
        .isEqualTo(LocalDate.of(2026, 10, 27));
  }

  @Test
  void movesSaturdayAndSundayToMonday() {
    assertThat(BusinessDays.nextOnOrAfter(LocalDate.of(2026, 10, 31)))
        .isEqualTo(LocalDate.of(2026, 11, 3));
    assertThat(BusinessDays.nextOnOrAfter(LocalDate.of(2026, 11, 1)))
        .isEqualTo(LocalDate.of(2026, 11, 3));
  }

  @Test
  void skipsAHolidayThatFollowsTheWeekend() {
    // 2026-11-02 é Finados e cai numa segunda
    assertThat(BusinessDays.nextOnOrAfter(LocalDate.of(2026, 11, 1)))
        .isEqualTo(LocalDate.of(2026, 11, 3));
  }

  @Test
  void movesTheMovableHolidaysFromEaster() {
    assertThat(BusinessDays.easter(2024)).isEqualTo(LocalDate.of(2024, 3, 31));
    assertThat(BusinessDays.easter(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
    assertThat(BusinessDays.isBusinessDay(LocalDate.of(2026, 4, 3))).isFalse();
    assertThat(BusinessDays.isBusinessDay(LocalDate.of(2026, 2, 17))).isFalse();
    assertThat(BusinessDays.isBusinessDay(LocalDate.of(2026, 6, 4))).isFalse();
  }

  @Test
  void handlesLeapYearAndTheLastDayOfFebruary() {
    // 29/02/2028 é terça de Carnaval; 28/02/2027 é domingo. Os dois vão para 1º de março
    assertThat(BusinessDays.nextOnOrAfter(LocalDate.of(2028, 2, 29)))
        .isEqualTo(LocalDate.of(2028, 3, 1));
    assertThat(BusinessDays.nextOnOrAfter(LocalDate.of(2027, 2, 28)))
        .isEqualTo(LocalDate.of(2027, 3, 1));
  }

  @Test
  void countsBlackConsciousnessDayOnlyFrom2024() {
    assertThat(BusinessDays.isBusinessDay(LocalDate.of(2023, 11, 20))).isTrue();
    assertThat(BusinessDays.isBusinessDay(LocalDate.of(2025, 11, 20))).isFalse();
  }

  @Test
  void payDateMovesAnInvoiceDueOnASaturdayToMonday() {
    CardBilling billing = new CardBilling(25, 31);
    // vencimento nominal 31/10/2026 é sábado
    assertThat(billing.dueDate(LocalDate.of(2026, 10, 10))).isEqualTo(LocalDate.of(2026, 10, 31));
    assertThat(billing.payDate(LocalDate.of(2026, 10, 10))).isEqualTo(LocalDate.of(2026, 11, 3));
  }
}
