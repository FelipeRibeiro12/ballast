package com.felipe.ballast.entries;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InstallmentPlanTest {

  private static final CardBilling BILLING = new CardBilling(15, 25);
  private static final LocalDate PURCHASE = LocalDate.of(2026, 3, 10);

  private static List<InstallmentPlan.Part> split(String total, int count) {
    return InstallmentPlan.of(new BigDecimal(total), count, BILLING, PURCHASE);
  }

  private static BigDecimal sum(List<InstallmentPlan.Part> parts) {
    return parts.stream()
        .map(InstallmentPlan.Part::amount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  @Test
  void thousandInThreeLeavesTheCentOnTheLastPart() {
    List<InstallmentPlan.Part> parts = split("1000.00", 3);

    assertThat(parts)
        .extracting(InstallmentPlan.Part::amount)
        .containsExactly(
            new BigDecimal("333.33"), new BigDecimal("333.33"), new BigDecimal("333.34"));
    assertThat(sum(parts)).isEqualByComparingTo("1000.00");
  }

  @Test
  void hundredInFortyEightHasAFortyEighthPartWithTheRemainder() {
    List<InstallmentPlan.Part> parts = split("100.00", 48);

    assertThat(parts).hasSize(48);
    assertThat(parts.getFirst().amount()).isEqualTo(new BigDecimal("2.08"));
    assertThat(parts.get(46).amount()).isEqualTo(new BigDecimal("2.08"));
    // 47 x 2,08 = 97,76; o resto de 2,24 fica na última.
    assertThat(parts.getLast().amount()).isEqualTo(new BigDecimal("2.24"));
    assertThat(sum(parts)).isEqualByComparingTo("100.00");
  }

  @Test
  void fortySevenCentsInFortyEightGivesZeroToAllButTheLast() {
    List<InstallmentPlan.Part> parts = split("0.47", 48);

    assertThat(parts.getFirst().amount()).isEqualByComparingTo("0.00");
    assertThat(parts.getLast().amount()).isEqualByComparingTo("0.47");
    assertThat(sum(parts)).isEqualByComparingTo("0.47");
  }

  @Test
  void aTotalAtTheCeilingStillAddsUpAndKeepsScaleTwo() {
    List<InstallmentPlan.Part> parts = split("90071992547409.91", 7);

    assertThat(sum(parts)).isEqualTo(new BigDecimal("90071992547409.91"));
    assertThat(parts).allSatisfy(part -> assertThat(part.amount().scale()).isEqualTo(2));
    assertThat(parts.getLast().amount()).isGreaterThanOrEqualTo(parts.getFirst().amount());
  }

  @Test
  void numbersGoFromOneToTheCount() {
    assertThat(split("10.00", 4))
        .extracting(InstallmentPlan.Part::number)
        .containsExactly(1, 2, 3, 4);
  }

  // fechamento, vencimento, compra, primeira caixa, caixa da parcela 12 e da 3. Caixa em dia
  // útil (ADR-060): vencimento em fim de semana ou feriado passa para o próximo dia útil.
  @ParameterizedTest(name = "F{0} V{1} compra {2}")
  @CsvSource({
    // Vencimento 31 atravessando fevereiro comum (2027) e a virada de ano.
    "10, 31, 2026-12-05, 2026-12-31, 2027-02-01, 2027-03-01, 2027-03-31, 2027-11-30",
    // Vencimento 31 passando por fevereiro bissexto (2028).
    "10, 31, 2027-12-05, 2027-12-31, 2028-01-31, 2028-03-01, 2028-03-31, 2028-11-30",
    // Vencimento 30: fevereiro cai no último dia, e os outros meses voltam ao 30.
    "10, 30, 2026-12-05, 2026-12-30, 2027-02-01, 2027-03-01, 2027-03-30, 2027-11-30",
    "10, 30, 2027-12-05, 2027-12-30, 2028-01-31, 2028-03-01, 2028-03-30, 2028-11-30",
    // Vencimento 5, menor que o fechamento: a primeira vence no mês seguinte ao do fechamento.
    "25, 5, 2026-12-10, 2027-01-05, 2027-02-05, 2027-03-05, 2027-04-05, 2027-12-06",
    "25, 5, 2027-01-26, 2027-03-05, 2027-04-05, 2027-05-05, 2027-06-07, 2028-02-07"
  })
  void dueDatesStepOneMonthAtTheCardDueDay(
      int closing,
      int due,
      String purchase,
      String first,
      String second,
      String third,
      String fourth,
      String twelfth) {
    List<InstallmentPlan.Part> parts =
        InstallmentPlan.of(
            new BigDecimal("1200.00"),
            12,
            new CardBilling(closing, due),
            LocalDate.parse(purchase));

    assertThat(parts.get(0).cashDate()).isEqualTo(LocalDate.parse(first));
    assertThat(parts.get(1).cashDate()).isEqualTo(LocalDate.parse(second));
    assertThat(parts.get(2).cashDate()).isEqualTo(LocalDate.parse(third));
    assertThat(parts.get(3).cashDate()).isEqualTo(LocalDate.parse(fourth));
    assertThat(parts.get(11).cashDate()).isEqualTo(LocalDate.parse(twelfth));
  }

  @Test
  void theFirstPartDueDateIsTheInvoiceDueDateOfThePurchase() {
    List<InstallmentPlan.Part> parts = split("300.00", 3);

    assertThat(parts.getFirst().cashDate()).isEqualTo(BILLING.payDate(PURCHASE));
    assertThat(parts)
        .extracting(InstallmentPlan.Part::cashDate)
        .containsExactly(
            LocalDate.of(2026, 3, 25), LocalDate.of(2026, 4, 27), LocalDate.of(2026, 5, 25));
  }
}
