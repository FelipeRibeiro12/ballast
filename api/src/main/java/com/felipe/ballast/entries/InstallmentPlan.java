package com.felipe.ballast.entries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

// Divisão de uma compra parcelada (ADR-032). Pura: sem banco e sem relógio.
final class InstallmentPlan {

  record Part(int number, BigDecimal amount, LocalDate cashDate) {}

  private InstallmentPlan() {}

  // Valor de cada parcela menos a última.
  static BigDecimal each(BigDecimal total, int count) {
    return total.divide(BigDecimal.valueOf(count), 2, RoundingMode.DOWN);
  }

  // As parcelas menos a última usam a divisão truncada; a última leva o resto e a soma fecha no
  // total. DOWN porque arredondar para cima nas primeiras poderia deixar a última menor que as
  // outras (ou negativa); com DOWN o resto é sempre positivo e a última nunca é menor.
  // A parcela k vence k - 1 meses depois da primeira, no dia de vencimento do cartão, cortado no
  // último dia do mês curto e passado para o próximo dia útil (ADR-060).
  static List<Part> of(BigDecimal total, int count, CardBilling billing, LocalDate purchase) {
    BigDecimal each = each(total, count);
    YearMonth firstMonth = YearMonth.from(billing.dueDate(purchase));
    BigDecimal assigned = BigDecimal.ZERO;
    List<Part> parts = new ArrayList<>(count);
    for (int number = 1; number <= count; number++) {
      BigDecimal amount = number == count ? total.subtract(assigned) : each;
      assigned = assigned.add(amount);
      YearMonth month = firstMonth.plusMonths(number - 1L);
      LocalDate cashDate =
          BusinessDays.nextOnOrAfter(
              month.atDay(Math.min(billing.dueDay(), month.lengthOfMonth())));
      parts.add(new Part(number, amount, cashDate));
    }
    return parts;
  }
}
