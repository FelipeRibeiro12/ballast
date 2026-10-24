package com.felipe.ballast.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

// "Posso gastar quanto" (ADR-048): o menor saldo projetado de hoje até a véspera do próximo
// salário. É o quanto dá para gastar hoje sem faltar para nenhuma conta já prevista até lá.
@Service
class SpendableService {

  static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  // A janela cabe na projeção, que vai até 366 dias.
  private static final int MAX_DAYS = 366;

  // Number.MAX_SAFE_INTEGER em centavos.
  private static final BigDecimal LIMIT = new BigDecimal("90071992547409.91");

  record Benefit(UUID accountId, String name, BigDecimal spendable) {}

  // perDay nulo quando não sobra nada. salary nulo sem salário previsto na janela máxima.
  record Spendable(
      LocalDate today,
      LocalDate until,
      SpendableQueries.Salary salary,
      BigDecimal spendable,
      LocalDate lowestOn,
      int days,
      BigDecimal perDay,
      BigDecimal overdue,
      List<Benefit> benefits) {}

  private final ProjectionService projections;
  private final SpendableQueries queries;
  private final Clock clock;

  SpendableService(ProjectionService projections, SpendableQueries queries, Clock clock) {
    this.projections = projections;
    this.queries = queries;
    this.clock = clock;
  }

  // Vazio quando algum valor sai da faixa do JSON.
  Optional<Spendable> compute(UUID userId) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZONE);
    SpendableQueries.Salary salary =
        queries.nextSalary(userId, today, today.plusDays(MAX_DAYS)).orElse(null);
    // Sem salário à vista, a conta fecha no fim do mês, a outra data que todo mundo usa.
    LocalDate until =
        salary == null
            ? today.with(TemporalAdjusters.lastDayOfMonth())
            : salary.date().minusDays(1);
    int days = (int) ChronoUnit.DAYS.between(today, until) + 1;

    Optional<Projection> total = projected(userId, null, today, days);
    if (total.isEmpty()) {
      return Optional.empty();
    }
    List<Benefit> benefits = new ArrayList<>();
    for (SpendableQueries.Benefit benefit : queries.activeBenefits(userId)) {
      Optional<Projection> found = projected(userId, benefit.id(), today, days);
      if (found.isEmpty()) {
        return Optional.empty();
      }
      benefits.add(new Benefit(benefit.id(), benefit.name(), found.get().lowest().balance()));
    }
    if (salary != null && salary.amount().compareTo(LIMIT) > 0) {
      return Optional.empty();
    }

    Projection.Point lowest = total.get().lowest();
    BigDecimal spendable = lowest.balance();
    // Para baixo: o valor por dia nunca promete mais do que existe.
    BigDecimal perDay =
        spendable.signum() > 0
            ? spendable.divide(BigDecimal.valueOf(days), 2, RoundingMode.DOWN)
            : null;
    return Optional.of(
        new Spendable(
            today,
            until,
            salary,
            spendable,
            lowest.date(),
            days,
            perDay,
            total.get().overdue(),
            benefits));
  }

  private Optional<Projection> projected(UUID userId, UUID accountId, LocalDate today, int days) {
    return switch (projections.project(userId, accountId, today, days)) {
      case ProjectionService.Result.Found found -> Optional.of(found.projection());
      // Fora da faixa, ou a conta de benefício sumiu entre as duas leituras.
      case ProjectionService.Result.Refused refused -> Optional.empty();
    };
  }
}
