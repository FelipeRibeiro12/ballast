package com.felipe.ballast.recurrences;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

// Valor de cada parcela da divisão de salário, sem banco e sem Spring. A soma das parcelas é
// sempre o valor total (dados.md, regra do centavo).
final class SalarySplit {

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private SalarySplit() {}

  // Na ordem das parcelas. Em valor fixo a soma já foi conferida na borda; em percentual a
  // última parcela fecha a conta.
  static List<BigDecimal> amounts(BigDecimal total, Division division) {
    List<DivisionPart> parts = division.parts();
    List<BigDecimal> amounts = new ArrayList<>(parts.size());
    if (division.mode() == DivisionMode.AMOUNT) {
      parts.forEach(part -> amounts.add(part.fixedAmount()));
      return amounts;
    }
    BigDecimal assigned = BigDecimal.ZERO;
    for (int i = 0; i < parts.size() - 1; i++) {
      // DOWN e não HALF_UP: arredondar para cima várias parcelas pode passar do total, e o
      // resto da última ficaria negativo. Truncando, o resto nunca é negativo.
      BigDecimal amount =
          total.multiply(parts.get(i).percent()).divide(HUNDRED, 2, RoundingMode.DOWN);
      amounts.add(amount);
      assigned = assigned.add(amount);
    }
    amounts.add(total.subtract(assigned));
    return amounts;
  }
}
