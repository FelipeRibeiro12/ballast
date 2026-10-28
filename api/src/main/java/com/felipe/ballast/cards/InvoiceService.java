package com.felipe.ballast.cards;

import com.felipe.ballast.entries.CardBilling;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Monta a lista de faturas: os vencimentos vêm do ciclo do cartão e os valores, de duas consultas
// agregadas. Leitura pura, sem transação própria.
@Service
class InvoiceService {

  // Number.MAX_SAFE_INTEGER em centavos, a mesma faixa do saldo de conta.
  private static final BigDecimal LIMIT = new BigDecimal("90071992547409.91");

  enum Situation {
    OPEN("aberta"),
    CLOSED("fechada"),
    PAID("paga");

    private final String value;

    Situation(String value) {
      this.value = value;
    }

    String value() {
      return value;
    }
  }

  record Invoice(
      LocalDate due,
      LocalDate closing,
      BigDecimal total,
      BigDecimal paid,
      BigDecimal remaining,
      Situation situation) {}

  sealed interface Result {
    record Found(List<Invoice> invoices) implements Result {}

    enum Refused implements Result {
      NOT_FOUND,
      OUT_OF_RANGE
    }
  }

  private final InvoiceQueries queries;

  InvoiceService(InvoiceQueries queries) {
    this.queries = queries;
  }

  Result list(UUID userId, UUID accountId, LocalDate from, LocalDate to, LocalDate today) {
    Optional<CardBilling> billing = queries.findBilling(userId, accountId);
    if (billing.isEmpty()) {
      return Result.Refused.NOT_FOUND;
    }
    List<CardBilling.Cycle> cycles = billing.get().cycles(from, to);
    if (cycles.isEmpty()) {
      return new Result.Found(List.of());
    }
    LocalDate lastDue = cycles.getLast().due();
    // O pagamento de uma fatura vai do dia seguinte ao fechamento dela até o fechamento da
    // seguinte, então o atraso de poucos dias ainda é dela. Pode começar antes de from. Começa no
    // próprio fechamento porque, quando o vencimento cortado cai nesse dia, o dia é da fatura.
    LocalDate paymentsFrom = cycles.getFirst().closing();
    LocalDate paymentsTo = cycles.getLast().nextClosing();
    // Lançamento gravado antes do dia útil (ADR-060) tem data_caixa no dia nominal, que pode ser
    // alguns dias antes do vencimento real. O total da fatura soma do nominal ao real.
    Map<LocalDate, BigDecimal> totals =
        queries.totalsByCashDate(
            userId, accountId, billing.get().dueDate(cycles.getFirst().closing()), lastDue);
    Map<LocalDate, BigDecimal> payments =
        queries.paymentsByCashDate(userId, accountId, paymentsFrom, paymentsTo);

    List<Invoice> invoices = new ArrayList<>(cycles.size());
    for (CardBilling.Cycle cycle : cycles) {
      BigDecimal total = totalOf(totals, billing.get().dueDate(cycle.closing()), cycle.due());
      BigDecimal paid = paidIn(payments, billing.get(), cycle);
      BigDecimal remaining = total.subtract(paid);
      if (!fits(total) || !fits(paid) || !fits(remaining)) {
        return Result.Refused.OUT_OF_RANGE;
      }
      invoices.add(
          new Invoice(
              cycle.due(),
              cycle.closing(),
              total,
              paid,
              remaining,
              situation(today, cycle.closing(), remaining)));
    }
    return new Result.Found(invoices);
  }

  private static BigDecimal totalOf(
      Map<LocalDate, BigDecimal> totals, LocalDate nominalDue, LocalDate payDate) {
    BigDecimal sum = BigDecimal.ZERO;
    for (Map.Entry<LocalDate, BigDecimal> entry : totals.entrySet()) {
      if (!entry.getKey().isBefore(nominalDue) && !entry.getKey().isAfter(payDate)) {
        sum = sum.add(entry.getValue());
      }
    }
    return sum;
  }

  // Depois do fechamento da fatura e até o da seguinte, inclusive: (closing, nextClosing].
  // Fevereiro
  // com fechamento 30 e vencimento 31 corta os dois no dia 28: o pagamento desse dia é do
  // vencimento daquela fatura, então o dia entra na própria e sai da anterior (ADR-032).
  private static BigDecimal paidIn(
      Map<LocalDate, BigDecimal> payments, CardBilling billing, CardBilling.Cycle cycle) {
    // O dia nominal, não o útil: é ele que coincide com o fechamento (ADR-060).
    boolean startsOnClosingDay = billing.dueDate(cycle.closing()).equals(cycle.closing());
    boolean endsBeforeNextClosingDay =
        billing.dueDate(cycle.nextClosing()).equals(cycle.nextClosing());
    BigDecimal sum = BigDecimal.ZERO;
    for (Map.Entry<LocalDate, BigDecimal> entry : payments.entrySet()) {
      LocalDate day = entry.getKey();
      boolean afterStart =
          startsOnClosingDay ? !day.isBefore(cycle.closing()) : day.isAfter(cycle.closing());
      boolean beforeEnd =
          endsBeforeNextClosingDay
              ? day.isBefore(cycle.nextClosing())
              : !day.isAfter(cycle.nextClosing());
      if (afterStart && beforeEnd) {
        sum = sum.add(entry.getValue());
      }
    }
    return sum;
  }

  // Aberta é só questão de data: até o dia do fechamento, inclusive, ainda pode entrar compra.
  private static Situation situation(LocalDate today, LocalDate closing, BigDecimal remaining) {
    if (!today.isAfter(closing)) {
      return Situation.OPEN;
    }
    return remaining.signum() > 0 ? Situation.CLOSED : Situation.PAID;
  }

  private static boolean fits(BigDecimal value) {
    return value.abs().compareTo(LIMIT) <= 0;
  }
}
