package com.felipe.ballast.entries;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

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

  // Vencimento nominal, no dia configurado. Serve para achar o mês da fatura; o dinheiro sai em
  // payDate (ADR-060).
  public LocalDate dueDate(LocalDate purchase) {
    YearMonth closing = closingMonth(purchase);
    YearMonth due = dueDay > closingDay ? closing : closing.plusMonths(1);
    return clampedDay(due, dueDay);
  }

  // data_caixa da compra: o vencimento da fatura em que ela entra, passado para o próximo dia útil.
  public LocalDate payDate(LocalDate purchase) {
    return BusinessDays.nextOnOrAfter(dueDate(purchase));
  }

  // Uma fatura: fecha em closing, vence em due, e a seguinte fecha em nextClosing.
  public record Cycle(LocalDate closing, LocalDate due, LocalDate nextClosing) {}

  // Faturas com vencimento entre from e to, pontas incluídas, em ordem. Cada fatura é a do mês de
  // fechamento; o vencimento nunca vem antes de um mês de fechamento anterior ao dele, então
  // começar dois meses antes de from cobre qualquer combinação de dias.
  public List<Cycle> cycles(LocalDate from, LocalDate to) {
    List<Cycle> cycles = new ArrayList<>();
    for (YearMonth month = YearMonth.from(from).minusMonths(2);
        !month.isAfter(YearMonth.from(to));
        month = month.plusMonths(1)) {
      LocalDate due = payDate(month.atDay(1));
      if (!due.isBefore(from) && !due.isAfter(to)) {
        cycles.add(
            new Cycle(closingDate(month.atDay(1)), due, closingDate(month.plusMonths(1).atDay(1))));
      }
    }
    return cycles;
  }

  private YearMonth closingMonth(LocalDate purchase) {
    YearMonth month = YearMonth.from(purchase);
    return purchase.isAfter(clampedDay(month, closingDay)) ? month.plusMonths(1) : month;
  }

  private static LocalDate clampedDay(YearMonth month, int day) {
    return month.atDay(Math.min(day, month.lengthOfMonth()));
  }
}
