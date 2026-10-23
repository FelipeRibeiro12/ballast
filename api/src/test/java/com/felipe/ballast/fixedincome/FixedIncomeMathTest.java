package com.felipe.ballast.fixedincome;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class FixedIncomeMathTest {

  private static LocalDate d(String iso) {
    return LocalDate.parse(iso);
  }

  // Dias úteis de segunda a sexta entre as datas, com o mesmo CDI diário em todos.
  private static TreeMap<LocalDate, BigDecimal> flatCdi(String from, String to, String rate) {
    TreeMap<LocalDate, BigDecimal> cdi = new TreeMap<>();
    for (LocalDate day = d(from); !day.isAfter(d(to)); day = day.plusDays(1)) {
      if (day.getDayOfWeek().getValue() <= 5) {
        cdi.put(day, new BigDecimal(rate));
      }
    }
    return cdi;
  }

  @Test
  void theRootIsExactEnoughToComeBackToTheNumber() {
    BigDecimal value = new BigDecimal("1.125");

    BigDecimal root = FixedIncomeMath.root(value, 252);

    assertThat(root.pow(252, FixedIncomeMath.CONTEXT).subtract(value).abs())
        .isLessThan(new BigDecimal("1E-30"));
  }

  @Test
  void aPrefixadoAfter252BusinessDaysGrowsExactlyTheYearlyRate() {
    BigDecimal factor = FixedIncomeMath.prefixedFactor(new BigDecimal("12.5"), 252);

    assertThat(factor.setScale(10, RoundingMode.HALF_EVEN)).isEqualByComparingTo("1.1250000000");
  }

  @Test
  void aPrefixadoWithZeroDaysDoesNotGrow() {
    assertThat(FixedIncomeMath.prefixedFactor(new BigDecimal("12.5"), 0)).isEqualByComparingTo("1");
  }

  @Test
  void theCdiFactorMultipliesEachBusinessDayAtThePercentageOfTheCdi() {
    TreeMap<LocalDate, BigDecimal> cdi = new TreeMap<>();
    cdi.put(d("2026-10-01"), new BigDecimal("0.05"));
    cdi.put(d("2026-10-02"), new BigDecimal("0.04"));
    // 95% do CDI: (1 + 0,0005 x 0,95) x (1 + 0,0004 x 0,95)
    BigDecimal expected =
        new BigDecimal("1.000475").multiply(new BigDecimal("1.00038"), FixedIncomeMath.CONTEXT);

    BigDecimal factor =
        FixedIncomeMath.cdiFactor(cdi, d("2026-10-01"), d("2026-10-05"), new BigDecimal("95"));

    assertThat(factor).isEqualByComparingTo(expected);
  }

  @Test
  void theCdiOfTheEndDateItselfDoesNotCount() {
    TreeMap<LocalDate, BigDecimal> cdi = flatCdi("2026-10-01", "2026-10-02", "0.05");

    BigDecimal factor =
        FixedIncomeMath.cdiFactor(cdi, d("2026-10-01"), d("2026-10-02"), new BigDecimal("100"));

    assertThat(factor).isEqualByComparingTo("1.0005");
  }

  @Test
  void weekdaysAfterTheLastPublishedCdiUseTheLastRate() {
    TreeMap<LocalDate, BigDecimal> cdi = new TreeMap<>();
    cdi.put(d("2026-10-01"), new BigDecimal("0.05"));
    // 02/10 (sexta) e 05/10 (segunda) ainda sem publicação: usam 0,05; sábado e domingo não contam.
    BigDecimal expected = new BigDecimal("1.0005").pow(3, FixedIncomeMath.CONTEXT);

    BigDecimal factor =
        FixedIncomeMath.cdiFactor(cdi, d("2026-10-01"), d("2026-10-06"), new BigDecimal("100"));

    assertThat(factor).isEqualByComparingTo(expected);
    assertThat(FixedIncomeMath.businessDays(cdi, d("2026-10-01"), d("2026-10-06"))).isEqualTo(3);
  }

  @Test
  void businessDaysAreTheDaysWithCdiWhenTheyArePublished() {
    TreeMap<LocalDate, BigDecimal> cdi = flatCdi("2026-10-01", "2026-10-31", "0.05");
    cdi.remove(d("2026-10-12")); // feriado: o Banco Central não publica

    assertThat(FixedIncomeMath.businessDays(cdi, d("2026-10-09"), d("2026-10-14"))).isEqualTo(2);
  }

  @Test
  void theIpcaFactorCompoundsWholeMonthsAndProRatesTheParts() {
    Map<LocalDate, BigDecimal> ipca = new TreeMap<>();
    ipca.put(d("2026-01-01"), new BigDecimal("1"));
    ipca.put(d("2026-02-01"), new BigDecimal("2"));
    // Janeiro inteiro (1%) e metade de fevereiro (14 de 28 dias de 2%).
    BigDecimal expected =
        new BigDecimal("1.01")
            .multiply(FixedIncomeMath.root(new BigDecimal("1.02"), 2), FixedIncomeMath.CONTEXT);

    BigDecimal factor = FixedIncomeMath.ipcaFactor(ipca, d("2026-01-01"), d("2026-02-15"));

    assertThat(factor.subtract(expected).abs()).isLessThan(new BigDecimal("1E-25"));
  }

  @Test
  void aMonthWithoutPublishedIpcaUsesTheLastOne() {
    Map<LocalDate, BigDecimal> ipca = new TreeMap<>();
    ipca.put(d("2026-01-01"), new BigDecimal("1"));

    BigDecimal factor = FixedIncomeMath.ipcaFactor(ipca, d("2026-02-01"), d("2026-03-01"));

    assertThat(factor).isEqualByComparingTo("1.01");
  }

  @Test
  void theIofTableGoesFrom96PercentToZeroOnDay30() {
    assertThat(FixedIncomeMath.iofRate(1)).isEqualByComparingTo("0.96");
    assertThat(FixedIncomeMath.iofRate(15)).isEqualByComparingTo("0.50");
    assertThat(FixedIncomeMath.iofRate(29)).isEqualByComparingTo("0.03");
    assertThat(FixedIncomeMath.iofRate(30)).isEqualByComparingTo("0");
    assertThat(FixedIncomeMath.iofRate(400)).isEqualByComparingTo("0");
  }

  @Test
  void theIncomeTaxGoesDownWithTime() {
    List<BigDecimal> rates = new ArrayList<>();
    for (int days : new int[] {1, 180, 181, 360, 361, 720, 721}) {
      rates.add(FixedIncomeMath.incomeTaxRate(days));
    }

    assertThat(rates)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(
            new BigDecimal("0.225"),
            new BigDecimal("0.225"),
            new BigDecimal("0.20"),
            new BigDecimal("0.20"),
            new BigDecimal("0.175"),
            new BigDecimal("0.175"),
            new BigDecimal("0.15"));
  }

  @Test
  void aCdbPaysIofThenIncomeTaxOnWhatIsLeftOfTheGain() {
    // R$ 100 de rendimento em 15 dias: IOF 50% = 50,00; IR 22,5% de 50,00 = 11,25.
    FixedIncomeMath.Taxes taxes =
        FixedIncomeMath.taxes(
            FixedIncomeType.CDB, new BigDecimal("1000.00"), new BigDecimal("1100.00"), 15);

    assertThat(taxes.iof()).isEqualByComparingTo("50.00");
    assertThat(taxes.incomeTax()).isEqualByComparingTo("11.25");
    assertThat(taxes.net()).isEqualByComparingTo("1038.75");
  }

  @Test
  void eachTaxIsRoundedOnceHalfEven() {
    // Rendimento 10,05 em 200 dias: IR 20% = 2,01; rendimento 0,05 x 22,5% = 0,01125 vira 0,01.
    assertThat(
            FixedIncomeMath.taxes(
                    FixedIncomeType.CDB, new BigDecimal("1000.00"), new BigDecimal("1010.05"), 200)
                .incomeTax())
        .isEqualByComparingTo("2.01");
    assertThat(
            FixedIncomeMath.taxes(
                    FixedIncomeType.CDB, new BigDecimal("1000.00"), new BigDecimal("1000.05"), 100)
                .incomeTax())
        .isEqualByComparingTo("0.01");
  }

  @Test
  void lciAndLcaAreExemptAndALossHasNoTax() {
    FixedIncomeMath.Taxes lci =
        FixedIncomeMath.taxes(
            FixedIncomeType.LCI, new BigDecimal("1000.00"), new BigDecimal("1100.00"), 15);
    FixedIncomeMath.Taxes loss =
        FixedIncomeMath.taxes(
            FixedIncomeType.CDB, new BigDecimal("1000.00"), new BigDecimal("990.00"), 15);

    assertThat(lci.net()).isEqualByComparingTo("1100.00");
    assertThat(lci.incomeTax()).isEqualByComparingTo("0");
    assertThat(loss.iof()).isEqualByComparingTo("0");
    assertThat(loss.incomeTax()).isEqualByComparingTo("0");
    assertThat(loss.net()).isEqualByComparingTo("990.00");
  }
}
