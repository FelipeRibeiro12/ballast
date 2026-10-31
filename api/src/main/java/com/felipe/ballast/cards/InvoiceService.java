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
      Situation situation,
      boolean closedByHand) {}

  sealed interface Result {
    record Found(List<Invoice> invoices) implements Result {}

    enum Refused implements Result {
      NOT_FOUND,
      OUT_OF_RANGE
    }
  }

  enum CloseResult {
    DONE,
    NOT_FOUND,
    // Já passou do fechamento: a fatura está fechada pela data.
    ALREADY_CLOSED,
    // O período da fatura ainda não começou, não há o que fechar.
    NOT_STARTED
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
    Map<LocalDate, LocalDate> manual = queries.manualClosings(userId, accountId);
    // Fatura fechada à mão passa a receber pagamento desde o dia em que foi fechada.
    LocalDate firstStart = effectiveClosing(manual.get(cycles.getFirst().due()), paymentsFrom);
    Map<LocalDate, BigDecimal> payments =
        queries.paymentsByCashDate(userId, accountId, firstStart, paymentsTo);

    List<Invoice> invoices = new ArrayList<>(cycles.size());
    for (CardBilling.Cycle cycle : cycles) {
      BigDecimal total = totalOf(totals, billing.get().dueDate(cycle.closing()), cycle.due());
      LocalDate closedOn = manual.get(cycle.due());
      LocalDate nextClosedOn = manual.get(billing.get().payDate(cycle.nextClosing()));
      BigDecimal paid = paidIn(payments, billing.get(), cycle, closedOn, nextClosedOn);
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
              situation(today, cycle.closing(), closedOn != null, remaining),
              closedOn != null && !today.isAfter(cycle.closing())));
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
  // O fechamento à mão só antecipa a janela: o dia combinado depois do fechamento não vale.
  private static LocalDate effectiveClosing(LocalDate manual, LocalDate closing) {
    return manual != null && manual.isBefore(closing) ? manual : closing;
  }

  private static BigDecimal paidIn(
      Map<LocalDate, BigDecimal> payments,
      CardBilling billing,
      CardBilling.Cycle cycle,
      LocalDate closedOn,
      LocalDate nextClosedOn) {
    LocalDate manualStart = effectiveClosing(closedOn, cycle.closing());
    LocalDate manualEnd = effectiveClosing(nextClosedOn, cycle.nextClosing());
    boolean startsEarly = manualStart.isBefore(cycle.closing());
    boolean endsEarly = manualEnd.isBefore(cycle.nextClosing());
    // O dia nominal, não o útil: é ele que coincide com o fechamento (ADR-060).
    boolean startsOnClosingDay = billing.dueDate(cycle.closing()).equals(cycle.closing());
    boolean endsBeforeNextClosingDay =
        billing.dueDate(cycle.nextClosing()).equals(cycle.nextClosing());
    BigDecimal sum = BigDecimal.ZERO;
    for (Map.Entry<LocalDate, BigDecimal> entry : payments.entrySet()) {
      LocalDate day = entry.getKey();
      // Fechada à mão em manualStart: o dia é da fatura. A seguinte fechada à mão em manualEnd: o
      // dia já é dela.
      boolean afterStart =
          startsEarly
              ? !day.isBefore(manualStart)
              : startsOnClosingDay ? !day.isBefore(cycle.closing()) : day.isAfter(cycle.closing());
      boolean beforeEnd =
          endsEarly
              ? day.isBefore(manualEnd)
              : endsBeforeNextClosingDay
                  ? day.isBefore(cycle.nextClosing())
                  : !day.isAfter(cycle.nextClosing());
      if (afterStart && beforeEnd) {
        sum = sum.add(entry.getValue());
      }
    }
    return sum;
  }

  // Aberta é questão de data: até o dia do fechamento, inclusive, ainda pode entrar compra. Fechar
  // à mão tira a fatura de aberta antes disso.
  private static Situation situation(
      LocalDate today, LocalDate closing, boolean closedByHand, BigDecimal remaining) {
    if (!closedByHand && !today.isAfter(closing)) {
      return Situation.OPEN;
    }
    return remaining.signum() > 0 ? Situation.CLOSED : Situation.PAID;
  }

  // Fecha à mão a fatura que vence em due. O vencimento tem de ser de uma fatura do cartão.
  CloseResult close(UUID userId, UUID accountId, LocalDate due, LocalDate today) {
    Optional<CardBilling.Cycle> cycle = cycleOf(userId, accountId, due);
    if (cycle.isEmpty()) {
      return CloseResult.NOT_FOUND;
    }
    CardBilling.Cycle found = cycle.get();
    if (today.isAfter(found.closing())) {
      return CloseResult.ALREADY_CLOSED;
    }
    // Só fecha a fatura que já está recebendo compra, não uma de meses à frente.
    CardBilling billing = queries.findBilling(userId, accountId).orElseThrow();
    if (!today.isAfter(billing.closingDate(found.closing().minusMonths(1).withDayOfMonth(1)))) {
      return CloseResult.NOT_STARTED;
    }
    queries.closeInvoice(userId, accountId, due, today);
    return CloseResult.DONE;
  }

  CloseResult reopen(UUID userId, UUID accountId, LocalDate due, LocalDate today) {
    Optional<CardBilling.Cycle> cycle = cycleOf(userId, accountId, due);
    if (cycle.isEmpty()) {
      return CloseResult.NOT_FOUND;
    }
    if (today.isAfter(cycle.get().closing())) {
      return CloseResult.ALREADY_CLOSED;
    }
    queries.reopenInvoice(userId, accountId, due);
    return CloseResult.DONE;
  }

  private Optional<CardBilling.Cycle> cycleOf(UUID userId, UUID accountId, LocalDate due) {
    return queries
        .findBilling(userId, accountId)
        .flatMap(
            billing ->
                billing.cycles(due, due).stream().filter(c -> c.due().equals(due)).findFirst());
  }

  private static boolean fits(BigDecimal value) {
    return value.abs().compareTo(LIMIT) <= 0;
  }
}
