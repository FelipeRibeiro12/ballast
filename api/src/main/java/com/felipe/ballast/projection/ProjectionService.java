package com.felipe.ballast.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Monta a série diária a partir da consulta. Leitura pura: sem transação própria.
@Service
class ProjectionService {

  // Number.MAX_SAFE_INTEGER em centavos, a mesma faixa do saldo de conta.
  private static final BigDecimal LIMIT = new BigDecimal("90071992547409.91");

  sealed interface Result {
    record Found(Projection projection) implements Result {}

    enum Refused implements Result {
      ACCOUNT_NOT_FOUND,
      CRYPTO_ACCOUNT,
      OUT_OF_RANGE
    }
  }

  private final ProjectionQueries queries;

  ProjectionService(ProjectionQueries queries) {
    this.queries = queries;
  }

  Result project(UUID userId, UUID accountId, LocalDate today, int days) {
    return project(userId, accountId, today, days, false, false);
  }

  Result project(
      UUID userId,
      UUID accountId,
      LocalDate today,
      int days,
      boolean excludeThirdParty,
      boolean includeBenefits) {
    ProjectionRows rows =
        queries.load(userId, accountId, today, days, excludeThirdParty, includeBenefits);
    // Com contaId, escopo vazio quer dizer conta de outro usuário ou inexistente.
    if (accountId != null && rows.accountCount() == 0) {
      return Result.Refused.ACCOUNT_NOT_FOUND;
    }
    if (rows.cryptoCount() > 0) {
      return Result.Refused.CRYPTO_ACCOUNT;
    }

    // O saldo do primeiro dia já traz tudo que tem data de caixa antes de hoje.
    BigDecimal balance = rows.initialBalance().add(rows.balanceBeforeToday());
    BigDecimal currentBalance = rows.initialBalance().add(rows.realized());
    boolean fits = fits(currentBalance) && fits(rows.overdue());

    List<Projection.Day> series = new ArrayList<>(rows.days().size());
    Projection.Point lowest = null;
    for (ProjectionRows.DayRow row : rows.days()) {
      balance = balance.add(row.inflow()).subtract(row.outflow());
      fits = fits && fits(row.inflow()) && fits(row.outflow()) && fits(balance);
      series.add(new Projection.Day(row.date(), row.inflow(), row.outflow(), balance));
      // Menor estrito: em empate fica o primeiro dia.
      if (lowest == null || balance.compareTo(lowest.balance()) < 0) {
        lowest = new Projection.Point(row.date(), balance);
      }
    }
    if (!fits) {
      return Result.Refused.OUT_OF_RANGE;
    }
    return new Result.Found(
        new Projection(
            today,
            today.plusDays(days - 1L),
            accountId,
            currentBalance,
            rows.overdue(),
            lowest,
            series));
  }

  private static boolean fits(BigDecimal amount) {
    return amount.abs().compareTo(LIMIT) <= 0;
  }
}
