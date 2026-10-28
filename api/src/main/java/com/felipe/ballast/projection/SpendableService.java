package com.felipe.ballast.projection;

import com.felipe.ballast.entries.CardBilling;
import com.felipe.ballast.fixedincome.CdiYield;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

// "Posso gastar quanto" (ADR-048, ADR-063): o menor saldo projetado de hoje até o fim da janela, mais o
// rendimento estimado dos cofrinhos até o dia desse menor saldo. A janela vem do dia que o usuário
// escolheu, ou do ciclo da fatura âncora, ou do fim do mês sem cartão.
@Service
class SpendableService {

  static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  // A janela cabe na projeção, que vai até 366 dias.
  private static final int MAX_DAYS = 366;

  enum Mode {
    CUSTOM_DAY,
    INVOICE_CYCLE,
    END_OF_MONTH
  }

  record Benefit(UUID accountId, String name, BigDecimal spendable) {}

  // perDay nulo quando não sobra nada. limitDay só no modo CUSTOM_DAY.
  record Spendable(
      LocalDate today,
      LocalDate until,
      Mode mode,
      Integer limitDay,
      BigDecimal spendable,
      BigDecimal savingsYield,
      LocalDate lowestOn,
      int days,
      BigDecimal perDay,
      BigDecimal overdue,
      List<Benefit> benefits) {}

  private final ProjectionService projections;
  private final SpendableQueries queries;
  private final CdiYield cdiYield;
  private final Clock clock;

  SpendableService(
      ProjectionService projections, SpendableQueries queries, CdiYield cdiYield, Clock clock) {
    this.projections = projections;
    this.queries = queries;
    this.cdiYield = cdiYield;
    this.clock = clock;
  }

  // Vazio quando algum valor sai da faixa do JSON.
  Optional<Spendable> compute(UUID userId) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZONE);
    Optional<Integer> limitDay = queries.limitDay(userId);
    Mode mode;
    LocalDate until;
    if (limitDay.isPresent()) {
      mode = Mode.CUSTOM_DAY;
      until = nextOccurrenceOfDay(today, limitDay.get());
    } else {
      Optional<CardBilling> anchor = queries.anchorCard(userId);
      if (anchor.isPresent()) {
        mode = Mode.INVOICE_CYCLE;
        until = nextPayDate(anchor.get(), today).minusDays(1);
      } else {
        mode = Mode.END_OF_MONTH;
        until = today.with(TemporalAdjusters.lastDayOfMonth());
      }
    }
    int days = (int) Math.min(MAX_DAYS, ChronoUnit.DAYS.between(today, until) + 1);
    until = today.plusDays(days - 1L);

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

    Projection.Point lowest = total.get().lowest();
    BigDecimal savingsYield = savingsYield(userId, today, lowest.date());
    BigDecimal spendable = lowest.balance().add(savingsYield);
    // Para baixo: o valor por dia nunca promete mais do que existe.
    BigDecimal perDay =
        spendable.signum() > 0
            ? spendable.divide(BigDecimal.valueOf(days), 2, RoundingMode.DOWN)
            : null;
    return Optional.of(
        new Spendable(
            today,
            until,
            mode,
            limitDay.orElse(null),
            spendable,
            savingsYield,
            lowest.date(),
            days,
            perDay,
            total.get().overdue(),
            benefits));
  }

  // Nulo volta ao ciclo da fatura. Só grava a preferência; nenhum lançamento muda.
  void configure(UUID userId, Integer limitDay) {
    if (limitDay == null) {
      queries.clearLimitDay(userId);
    } else {
      queries.saveLimitDay(userId, limitDay);
    }
  }

  // Dia de 1 a 31, cortado no último do mês curto. Hoje conta: se o dia ainda não passou, a janela
  // acaba neste mês; senão, no próximo.
  static LocalDate nextOccurrenceOfDay(LocalDate today, int day) {
    LocalDate thisMonth = clamp(YearMonth.from(today), day);
    return thisMonth.isBefore(today) ? clamp(YearMonth.from(today).plusMonths(1), day) : thisMonth;
  }

  // Primeiro pagamento da fatura depois de hoje, já em dia útil. Hoje é dia de pagar: o ciclo novo
  // começou e a janela vai até o pagamento seguinte.
  static LocalDate nextPayDate(CardBilling billing, LocalDate today) {
    return billing.cycles(today.plusDays(1), today.plusMonths(3)).getFirst().due();
  }

  private static LocalDate clamp(YearMonth month, int day) {
    return month.atDay(Math.min(day, month.lengthOfMonth()));
  }

  // Saldo do cofrinho parado de hoje até o dia do menor saldo, a partir do dia seguinte: o que já
  // rendeu até hoje está no saldo ou ainda não foi creditado.
  private BigDecimal savingsYield(UUID userId, LocalDate today, LocalDate lowestOn) {
    List<SpendableQueries.SavingsPot> pots = queries.savingsPots(userId);
    BigDecimal total = BigDecimal.ZERO.setScale(2);
    if (pots.isEmpty() || !lowestOn.isAfter(today)) {
      return total;
    }
    NavigableMap<LocalDate, BigDecimal> cdi = cdiYield.load(today);
    for (SpendableQueries.SavingsPot pot : pots) {
      total =
          total.add(CdiYield.estimate(cdi, pot.balance(), pot.cdiPercent(), today, lowestOn));
    }
    return total;
  }

  private Optional<Projection> projected(UUID userId, UUID accountId, LocalDate today, int days) {
    // Compra de terceiro não é gasto do dono (ADR-062).
    return switch (projections.project(userId, accountId, today, days, true)) {
      case ProjectionService.Result.Found found -> Optional.of(found.projection());
      // Fora da faixa, ou a conta de benefício sumiu entre as duas leituras.
      case ProjectionService.Result.Refused refused -> Optional.empty();
    };
  }
}
