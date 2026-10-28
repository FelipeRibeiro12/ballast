package com.felipe.ballast.fixedincome;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CdiYieldTest {

  private static TreeMap<LocalDate, BigDecimal> cdi() {
    TreeMap<LocalDate, BigDecimal> cdi = new TreeMap<>();
    cdi.put(LocalDate.of(2026, 10, 30), new BigDecimal("0.05"));
    return cdi;
  }

  @Test
  void yieldsFiveBusinessDaysAtTheLastKnownRate() {
    // segunda a segunda: 5 dias úteis, 1000 x (1,0005^5 - 1) = 2,501 e o DOWN corta em 2,50
    BigDecimal yield =
        CdiYield.estimate(
            cdi(),
            new BigDecimal("1000.00"),
            new BigDecimal("100"),
            LocalDate.of(2026, 11, 2),
            LocalDate.of(2026, 11, 9));

    assertThat(yield).isEqualByComparingTo("2.50");
    assertThat(yield.scale()).isEqualTo(2);
  }

  @Test
  void scalesWithThePercentOfCdi() {
    BigDecimal yield =
        CdiYield.estimate(
            cdi(),
            new BigDecimal("1000.00"),
            new BigDecimal("110"),
            LocalDate.of(2026, 11, 2),
            LocalDate.of(2026, 11, 9));

    assertThat(yield).isEqualByComparingTo("2.75");
  }

  @Test
  void doesNotYieldOnZeroOrNegativeBalanceOrEmptyRange() {
    LocalDate from = LocalDate.of(2026, 11, 2);
    LocalDate to = LocalDate.of(2026, 11, 9);
    BigDecimal hundred = new BigDecimal("100");

    assertThat(CdiYield.estimate(cdi(), BigDecimal.ZERO, hundred, from, to)).isZero();
    assertThat(CdiYield.estimate(cdi(), new BigDecimal("-5"), hundred, from, to)).isZero();
    assertThat(CdiYield.estimate(cdi(), new BigDecimal("10"), hundred, from, from)).isZero();
    assertThat(CdiYield.estimate(new TreeMap<>(), new BigDecimal("10"), hundred, from, to))
        .isZero();
  }
}
