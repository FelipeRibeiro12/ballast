package com.felipe.ballast.entries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CardBillingTest {

  // fechamento, vencimento, compra, fechamento esperado, vencimento esperado
  @ParameterizedTest(name = "F{0} V{1} compra {2} fecha {3} vence {4}")
  @CsvSource({
    // Fechamento 31 em fevereiro de ano comum: fecha no dia 28, e a compra do dia 28 entra.
    "31, 10, 2026-02-28, 2026-02-28, 2026-03-10",
    "31, 10, 2026-02-27, 2026-02-28, 2026-03-10",
    "31, 10, 2026-03-01, 2026-03-31, 2026-04-10",
    // Mesmo caso em ano bissexto: fecha no dia 29, e a compra do dia 28 ainda cabe.
    "31, 10, 2024-02-28, 2024-02-29, 2024-03-10",
    "31, 10, 2024-02-29, 2024-02-29, 2024-03-10",
    "31, 10, 2024-03-01, 2024-03-31, 2024-04-10",
    // Vencimento menor que o fechamento vence no mês seguinte ao do fechamento.
    "25, 5, 2026-03-10, 2026-03-25, 2026-04-05",
    "25, 5, 2026-03-26, 2026-04-25, 2026-05-05",
    // Vencimento maior que o fechamento vence no mesmo mês.
    "10, 20, 2026-03-05, 2026-03-10, 2026-03-20",
    "10, 20, 2026-03-11, 2026-04-10, 2026-04-20",
    // Vencimento igual ao fechamento vence no mês seguinte.
    "15, 15, 2026-03-15, 2026-03-15, 2026-04-15",
    "15, 15, 2026-03-14, 2026-03-15, 2026-04-15",
    // Compra no próprio dia do fechamento entra na fatura que fecha naquele dia.
    "15, 5, 2026-03-15, 2026-03-15, 2026-04-05",
    // Compra no dia seguinte ao fechamento vai para a fatura do mês seguinte.
    "15, 5, 2026-03-16, 2026-04-15, 2026-05-05",
    // Virada de ano.
    "15, 5, 2026-12-20, 2027-01-15, 2027-02-05",
    "15, 5, 2026-12-10, 2026-12-15, 2027-01-05",
    "15, 20, 2026-12-16, 2027-01-15, 2027-01-20",
    // Fechamento 30 em fevereiro e na compra de 31 de janeiro (fecha no mês seguinte, curto).
    "30, 10, 2026-02-28, 2026-02-28, 2026-03-10",
    "30, 10, 2026-01-31, 2026-02-28, 2026-03-10",
    "30, 10, 2024-01-31, 2024-02-29, 2024-03-10",
    // Vencimento 30 e 31 em mês curto: cai no último dia.
    "10, 30, 2026-02-05, 2026-02-10, 2026-02-28",
    "10, 31, 2026-02-05, 2026-02-10, 2026-02-28",
    "10, 30, 2024-02-05, 2024-02-10, 2024-02-29",
    "10, 31, 2026-04-05, 2026-04-10, 2026-04-30",
    "10, 31, 2026-01-05, 2026-01-10, 2026-01-31",
    // Vencimento 31 e fechamento 31: V <= F, vence no mês seguinte, no último dia dele.
    "31, 31, 2026-01-31, 2026-01-31, 2026-02-28",
    "31, 31, 2026-04-30, 2026-04-30, 2026-05-31",
    // Fechamento 1: toda compra depois do dia 1 vai para o mês seguinte.
    "1, 10, 2026-03-01, 2026-03-01, 2026-03-10",
    "1, 10, 2026-03-02, 2026-04-01, 2026-04-10",
    // Fechamento 30 com vencimento 31 em fevereiro: V > F, mesmo mês, os dois no dia 28.
    "30, 31, 2026-02-10, 2026-02-28, 2026-02-28",
  })
  void computesClosingAndDueDate(
      int closingDay, int dueDay, LocalDate purchase, LocalDate closing, LocalDate due) {
    CardBilling billing = new CardBilling(closingDay, dueDay);

    assertThat(billing.closingDate(purchase)).isEqualTo(closing);
    assertThat(billing.dueDate(purchase)).isEqualTo(due);
  }

  @ParameterizedTest
  @CsvSource({"0, 10", "32, 10", "10, 0", "10, 32", "-1, 10"})
  void rejectsDaysOutsideOneToThirtyOne(int closingDay, int dueDay) {
    assertThatThrownBy(() -> new CardBilling(closingDay, dueDay))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsTheLimitDays() {
    new CardBilling(1, 1);
    new CardBilling(31, 31);
  }

  @Test
  void listsEveryCycleWhoseDueDateFallsInTheRangeWithTheNextClosing() {
    CardBilling billing = new CardBilling(15, 25);

    assertThat(billing.cycles(LocalDate.of(2026, 2, 25), LocalDate.of(2026, 4, 27)))
        .containsExactly(
            new CardBilling.Cycle(
                LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 25), LocalDate.of(2026, 3, 15)),
            new CardBilling.Cycle(
                LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 25), LocalDate.of(2026, 4, 15)),
            new CardBilling.Cycle(
                LocalDate.of(2026, 4, 15), LocalDate.of(2026, 4, 27), LocalDate.of(2026, 5, 15)));
  }

  @Test
  void leavesOutADueDateOneDayOutsideTheRange() {
    CardBilling billing = new CardBilling(15, 25);

    assertThat(billing.cycles(LocalDate.of(2026, 3, 26), LocalDate.of(2026, 4, 24))).isEmpty();
  }

  @Test
  void closingOnThe31stFallsOnTheLastDayOfFebruaryAndTheDueDateIsInMarch() {
    CardBilling billing = new CardBilling(31, 10);

    assertThat(billing.cycles(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
        .containsExactly(
            new CardBilling.Cycle(
                LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 31)));
    assertThat(billing.cycles(LocalDate.of(2028, 3, 1), LocalDate.of(2028, 3, 31)))
        .extracting(CardBilling.Cycle::closing)
        .containsExactly(LocalDate.of(2028, 2, 29));
  }

  @Test
  void aDueDayBeforeTheClosingDayPutsTheDueDateInTheMonthAfterTheClosing() {
    CardBilling billing = new CardBilling(25, 5);

    assertThat(billing.cycles(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
        .containsExactly(
            new CardBilling.Cycle(
                LocalDate.of(2026, 3, 25), LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 25)));
  }
}
