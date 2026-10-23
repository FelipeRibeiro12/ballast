package com.felipe.ballast.budgets;

import com.felipe.ballast.web.Constraints;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
class BudgetService {

  // "Hoje" e o mês corrente são os do usuário, como no lançamento.
  static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  // 9007199254740991 centavos em reais. Comparado com compareTo: a escala não conta.
  private static final BigDecimal LIMIT = new BigDecimal("90071992547409.91");

  enum Outcome {
    SAVED,
    NOT_FOUND,
    NOT_EXPENSE
  }

  // left é teto menos gasto e fica negativo quando o gasto passa do teto.
  record Budget(
      UUID categoryId,
      BigDecimal ceiling,
      YearMonth since,
      BigDecimal spent,
      BigDecimal planned,
      BigDecimal left) {}

  private final BudgetQueries queries;
  private final Clock clock;

  BudgetService(BudgetQueries queries, Clock clock) {
    this.queries = queries;
    this.clock = clock;
  }

  YearMonth currentMonth() {
    return YearMonth.from(today());
  }

  // Vazio quando alguma soma sai da faixa do JSON.
  Optional<List<Budget>> list(UUID userId, YearMonth month) {
    List<Budget> budgets =
        queries.consumption(userId, month, today()).stream()
            .map(
                c ->
                    new Budget(
                        c.categoryId(),
                        c.ceiling(),
                        YearMonth.from(c.since()),
                        c.spent(),
                        c.planned(),
                        c.ceiling().subtract(c.spent())))
            .toList();
    boolean fits =
        budgets.stream().allMatch(b -> fits(b.spent()) && fits(b.planned()) && fits(b.left()));
    return fits ? Optional.of(budgets) : Optional.empty();
  }

  // ceiling nulo encerra o orçamento a partir do mês.
  Outcome set(UUID userId, UUID categoryId, YearMonth from, BigDecimal ceiling) {
    Optional<String> type = queries.categoryType(userId, categoryId);
    if (type.isEmpty()) {
      return Outcome.NOT_FOUND;
    }
    if (!type.get().equals("despesa")) {
      return Outcome.NOT_EXPENSE;
    }
    try {
      queries.upsert(userId, categoryId, from.atDay(1), ceiling);
    } catch (DataIntegrityViolationException e) {
      // A categoria sumiu entre a leitura e a escrita.
      if (Constraints.isViolated(e, "orcamento_categoria_despesa_do_usuario")) {
        return Outcome.NOT_FOUND;
      }
      throw e;
    }
    return Outcome.SAVED;
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZONE);
  }

  private static boolean fits(BigDecimal amount) {
    return amount.abs().compareTo(LIMIT) <= 0;
  }
}
