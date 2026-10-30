package com.felipe.ballast.savings;

import com.felipe.ballast.entries.BusinessDays;
import com.felipe.ballast.entries.ImportedEntries;
import com.felipe.ballast.fixedincome.CdiYield;
import com.felipe.ballast.web.LockTimeouts;
import com.felipe.ballast.web.UnexpectedErrorLog;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.NavigableMap;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;

// Rendimento diário do cofrinho (ADR-074). Cada dia de uma conta é uma transação própria, com a
// conta travada, para o job e o POST nunca lançarem o mesmo dia duas vezes.
@Service
class SavingsYieldService {

  private static final Logger log = LoggerFactory.getLogger(SavingsYieldService.class);

  static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  static final String ENTRY_TITLE = "Rendimento do cofrinho (estimado)";
  static final String CATEGORY_NAME = "Rendimento";
  static final int CATCH_UP_DAYS = 7;
  static final int BATCH_SIZE = 100;

  // Mesmo prazo curto da materialização das recorrências (ADR-026): trava ocupada pula a conta.
  private static final Duration JOB_LOCK_TIMEOUT = Duration.ofMillis(200);

  // Antes de qualquer UUID v7: o primeiro lote começa do início da chave primária.
  private static final UUID BEFORE_ALL = new UUID(0L, 0L);

  sealed interface Result {
    record Launched(BigDecimal amount) implements Result {}

    enum Skipped implements Result {
      NOT_FOUND,
      ALREADY_LAUNCHED,
      NO_CDI,
      NO_YIELD,
      NO_PERCENT
    }
  }

  record Summary(int accounts, int launched, int skipped, int failures) {}

  private final SavingsYieldQueries queries;
  private final WriteTransaction transaction;
  private final CdiYield cdiYield;
  private final ImportedEntries importedEntries;
  private final Clock clock;

  SavingsYieldService(
      SavingsYieldQueries queries,
      WriteTransaction transaction,
      CdiYield cdiYield,
      ImportedEntries importedEntries,
      Clock clock) {
    this.queries = queries;
    this.transaction = transaction;
    this.cdiYield = cdiYield;
    this.importedEntries = importedEntries;
    this.clock = clock;
  }

  LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZONE);
  }

  // O POST do usuário espera a trava pelo prazo normal das escritas, não pelo do job.
  Result runNow(UUID userId, UUID accountId) {
    return runDay(userId, accountId, today(), WriteTransaction.DEFAULT_LOCK_TIMEOUT);
  }

  Result runDay(UUID userId, UUID accountId, LocalDate day, Duration lockTimeout) {
    return transaction.execute(lockTimeout, status -> runDayLocked(status, userId, accountId, day));
  }

  // A janela de rendimento é [L, day): L é o último dia creditado da conta, ou o dia útil anterior
  // na primeira vez. O saldo é o de antes de L porque o crédito de L ainda não rendeu (ADR-074).
  private Result runDayLocked(
      TransactionStatus status, UUID userId, UUID accountId, LocalDate day) {
    var pot = queries.lockPot(userId, accountId);
    if (pot.isEmpty()) {
      return Result.Skipped.NOT_FOUND;
    }
    if (!BusinessDays.isBusinessDay(day)) {
      return Result.Skipped.NO_YIELD;
    }
    if (queries.hasMarker(userId, accountId, day)) {
      return Result.Skipped.ALREADY_LAUNCHED;
    }
    BigDecimal percent = pot.get().percent();
    if (percent == null) {
      return Result.Skipped.NO_PERCENT;
    }
    LocalDate last =
        queries.lastMarker(userId, accountId).orElseGet(() -> previousBusinessDay(day));
    if (!last.isBefore(day)) {
      return Result.Skipped.ALREADY_LAUNCHED;
    }
    NavigableMap<LocalDate, BigDecimal> cdi = cdiYield.load(last);
    if (cdi.isEmpty()) {
      return Result.Skipped.NO_CDI;
    }
    BigDecimal balance = queries.realizedBalanceBefore(userId, accountId, last);
    if (balance.signum() <= 0) {
      // Nada rendeu, mas a janela avança: sem o marcador o saldo de hoje renderia os dias de antes.
      queries.insertMarker(newId(), userId, accountId, day, null);
      return Result.Skipped.NO_YIELD;
    }
    BigDecimal amount = CdiYield.estimate(cdi, balance, percent, last, day);
    if (amount.signum() <= 0) {
      // Menos de um centavo: sem marcador, a janela acumula até dar pelo menos um centavo.
      return Result.Skipped.NO_YIELD;
    }
    var category = queries.incomeCategory(userId, CATEGORY_NAME);
    if (category.isEmpty()) {
      log.warn("cofrinho sem categoria Rendimento usuario_id={} conta_id={}", userId, accountId);
      return Result.Skipped.NO_YIELD;
    }
    ImportedEntries.Created created =
        importedEntries.create(
            status,
            userId,
            new ImportedEntries.NewEntry(
                "receita", ENTRY_TITLE, null, amount, category.get(), accountId, day, null, null));
    if (!created.saved()) {
      status.setRollbackOnly();
      log.warn(
          "rendimento do cofrinho recusado motivo={} usuario_id={} conta_id={}",
          created.failure(),
          userId,
          accountId);
      return Result.Skipped.NO_YIELD;
    }
    queries.insertMarker(newId(), userId, accountId, day, created.id());
    return new Result.Launched(amount);
  }

  private static LocalDate previousBusinessDay(LocalDate day) {
    LocalDate previous = day.minusDays(1);
    while (!BusinessDays.isBusinessDay(previous)) {
      previous = previous.minusDays(1);
    }
    return previous;
  }

  // A rodada do job: hoje e os dias úteis perdidos até 7 para trás, em ordem.
  Summary runAllToday() {
    return runAll(today());
  }

  Summary runAll(LocalDate today) {
    if (!BusinessDays.isBusinessDay(today)) {
      log.info("rendimento do cofrinho: hoje não é dia útil, nada a lançar");
      return new Summary(0, 0, 0, 0);
    }
    int accounts = 0;
    int launched = 0;
    int skipped = 0;
    int failures = 0;
    UUID after = BEFORE_ALL;
    while (true) {
      List<SavingsYieldQueries.PotRef> batch = queries.activePots(after, BATCH_SIZE);
      if (batch.isEmpty()) {
        break;
      }
      for (SavingsYieldQueries.PotRef ref : batch) {
        accounts++;
        after = ref.accountId();
        try {
          launched += catchUp(ref, today);
        } catch (RuntimeException e) {
          if (LockTimeouts.isLockTimeout(e)) {
            // Trava ocupada além do prazo do job: a conta fica para a próxima rodada, sem stack.
            skipped++;
            log.warn(
                "rendimento do cofrinho pulado por trava ocupada usuario_id={} conta_id={}",
                ref.userId(),
                ref.accountId());
            continue;
          }
          // Uma conta com problema não pode parar as outras.
          failures++;
          UnexpectedErrorLog.error(
              log,
              "falha ao lançar rendimento do cofrinho usuario_id=%s conta_id=%s"
                  .formatted(ref.userId(), ref.accountId()),
              e);
        }
      }
    }
    log.info(
        "rendimento do cofrinho contas={} lancados={} puladas={} falhas={}",
        accounts,
        launched,
        skipped,
        failures);
    return new Summary(accounts, launched, skipped, failures);
  }

  // Conta sem marcador só faz hoje. Com marcador, vai de max(L + 1, hoje - 7) até hoje, nos dias
  // úteis. Cada dia leva a janela desde o último crédito, então o dia pulado não se perde.
  private int catchUp(SavingsYieldQueries.PotRef ref, LocalDate today) {
    LocalDate first =
        queries
            .lastMarker(ref.userId(), ref.accountId())
            .map(
                last -> {
                  LocalDate limit = today.minusDays(CATCH_UP_DAYS);
                  return last.plusDays(1).isAfter(limit) ? last.plusDays(1) : limit;
                })
            .orElse(today);
    int launched = 0;
    for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
      if (!BusinessDays.isBusinessDay(day)) {
        continue;
      }
      Result result = runDay(ref.userId(), ref.accountId(), day, JOB_LOCK_TIMEOUT);
      if (result instanceof Result.Launched) {
        launched++;
      } else if (result == Result.Skipped.NO_CDI) {
        // Sem CDI guardado nenhum dia seguinte acha o que lançar.
        break;
      }
    }
    return launched;
  }

  private static UUID newId() {
    // O gerador do Hibernate não usa a sessão; é o mesmo dos @UuidGenerator das entidades.
    return UuidVersion7Strategy.INSTANCE.generateUuid(null);
  }
}
