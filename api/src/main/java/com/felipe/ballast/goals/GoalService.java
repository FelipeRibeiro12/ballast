package com.felipe.ballast.goals;

import com.felipe.ballast.web.Constraints;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
class GoalService {

  // "Hoje" e o mês corrente são os do usuário, como no lançamento.
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  // 9007199254740991 centavos em reais. Comparado com compareTo: a escala não conta.
  private static final BigDecimal LIMIT = new BigDecimal("90071992547409.91");

  record ProjectedGoal(GoalQueries.Goal goal, GoalProjection projection) {}

  enum DepositResult {
    SAVED,
    NOT_FOUND,
    FUTURE,
    WITHDRAWAL_TOO_BIG,
    OUT_OF_RANGE,
    TRANSFER_NOT_FOUND,
    TRANSFER_LINKED
  }

  // Violações que escapam da conferência quando outra requisição chega no meio: a transferência
  // foi apagada, ou ligada a outro aporte, entre a leitura e o INSERT.
  private static final String TRANSFER_FK = "aporte_meta_transferencia_mesmo_usuario";
  private static final String TRANSFER_UNIQUE = "aporte_meta_transferencia_unica";

  private final GoalQueries queries;
  private final WriteTransaction transaction;
  private final Clock clock;

  GoalService(GoalQueries queries, WriteTransaction transaction, Clock clock) {
    this.queries = queries;
    this.transaction = transaction;
    this.clock = clock;
  }

  List<ProjectedGoal> list(UUID userId) {
    LocalDate today = today();
    return queries.list(userId, today.withDayOfMonth(1)).stream()
        .map(goal -> project(goal, today))
        .toList();
  }

  Optional<ProjectedGoal> find(UUID userId, UUID id) {
    LocalDate today = today();
    return queries.find(userId, id, today.withDayOfMonth(1)).map(goal -> project(goal, today));
  }

  ProjectedGoal create(UUID userId, GoalQueries.Fields fields) {
    UUID id = queries.insert(userId, fields);
    return find(userId, id).orElseThrow();
  }

  Optional<ProjectedGoal> update(UUID userId, UUID id, GoalQueries.Fields fields) {
    if (queries.update(userId, id, fields) == 0) {
      return Optional.empty();
    }
    return find(userId, id);
  }

  boolean delete(UUID userId, UUID id) {
    return queries.delete(userId, id) > 0;
  }

  Optional<List<GoalQueries.Deposit>> deposits(UUID userId, UUID goalId) {
    if (queries.find(userId, goalId, today().withDayOfMonth(1)).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(queries.deposits(userId, goalId));
  }

  // Retirada não deixa o acumulado negativo, e nada passa do teto do JSON. transferId nulo é
  // aporte sem vínculo; com ele, a transferência tem de ser do usuário e estar livre (ADR-057).
  DepositResult deposit(
      UUID userId, UUID goalId, BigDecimal value, LocalDate date, UUID transferId) {
    if (date.isAfter(today())) {
      return DepositResult.FUTURE;
    }
    try {
      return transaction.execute(status -> depositLocked(userId, goalId, value, date, transferId));
    } catch (DataIntegrityViolationException e) {
      if (Constraints.isViolated(e, TRANSFER_UNIQUE)) {
        return DepositResult.TRANSFER_LINKED;
      }
      if (Constraints.isViolated(e, TRANSFER_FK)) {
        return DepositResult.TRANSFER_NOT_FOUND;
      }
      throw e;
    }
  }

  private DepositResult depositLocked(
      UUID userId, UUID goalId, BigDecimal value, LocalDate date, UUID transferId) {
    Optional<BigDecimal> saved = queries.lockAndSum(userId, goalId);
    if (saved.isEmpty()) {
      return DepositResult.NOT_FOUND;
    }
    if (transferId != null) {
      if (!queries.transferExists(userId, transferId)) {
        return DepositResult.TRANSFER_NOT_FOUND;
      }
      if (queries.transferIsLinked(userId, transferId)) {
        return DepositResult.TRANSFER_LINKED;
      }
    }
    DepositResult check = checkTotal(saved.get().add(value));
    if (check == DepositResult.SAVED) {
      queries.insertDeposit(userId, goalId, value, date, transferId);
    }
    return check;
  }

  // Apagar um depósito do qual uma retirada depende deixaria o acumulado negativo.
  DepositResult removeDeposit(UUID userId, UUID goalId, UUID depositId) {
    return transaction.execute(
        status -> {
          Optional<BigDecimal> saved = queries.lockAndSum(userId, goalId);
          if (saved.isEmpty()) {
            return DepositResult.NOT_FOUND;
          }
          Optional<BigDecimal> value = queries.depositValue(userId, goalId, depositId);
          if (value.isEmpty()) {
            return DepositResult.NOT_FOUND;
          }
          DepositResult check = checkTotal(saved.get().subtract(value.get()));
          if (check == DepositResult.SAVED) {
            queries.deleteDeposit(userId, goalId, depositId);
          }
          return check;
        });
  }

  private static DepositResult checkTotal(BigDecimal total) {
    if (total.signum() < 0) {
      return DepositResult.WITHDRAWAL_TOO_BIG;
    }
    if (total.compareTo(LIMIT) > 0) {
      return DepositResult.OUT_OF_RANGE;
    }
    return DepositResult.SAVED;
  }

  private static ProjectedGoal project(GoalQueries.Goal goal, LocalDate today) {
    return new ProjectedGoal(
        goal,
        GoalProjection.of(
            goal.target(),
            goal.saved(),
            goal.targetDate(),
            goal.monthlyPlan(),
            goal.recent(),
            today));
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZONE);
  }
}
