package com.felipe.ballast.entries;

import java.time.LocalDate;
import java.time.YearMonth;

// Regra da fatura (ADR-031). Dia maior que o último do mês cai no último dia: o fechamento 31 fecha
// em 28 ou 29 em fevereiro. A comparação "vencimento maior que fechamento" usa os dias
// configurados,
// não os já ajustados ao mês, para a fatura não mudar de mês conforme o calendário.
public record CardBilling(int closingDay, int dueDay) {

  public CardBilling {
    if (closingDay < 1 || closingDay > 31 || dueDay < 1 || dueDay > 31) {
      throw new IllegalArgumentException("dia de fechamento e de vencimento ficam entre 1 e 31");
    }
  }

  // Fechamento da fatura em que a compra entra. Até o dia do fechamento, inclusive, é a deste mês.
  public LocalDate closingDate(LocalDate purchase) {
    return clampedDay(closingMonth(purchase), closingDay);
  }

  // data_caixa da compra: o vencimento da fatura em que ela entra.
  public LocalDate dueDate(LocalDate purchase) {
    YearMonth closing = closingMonth(purchase);
    YearMonth due = dueDay > closingDay ? closing : closing.plusMonths(1);
    return clampedDay(due, dueDay);
  }

  private YearMonth closingMonth(LocalDate purchase) {
    YearMonth month = YearMonth.from(purchase);
    return purchase.isAfter(clampedDay(month, closingDay)) ? month.plusMonths(1) : month;
  }

  private static LocalDate clampedDay(YearMonth month, int day) {
    return month.atDay(Math.min(day, month.lengthOfMonth()));
  }
}
