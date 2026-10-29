package com.felipe.ballast.crypto;

import com.felipe.ballast.fixedincome.FixedIncomeService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Só leitura. Valor e soma em reais saem já na escala de centavos; o controller só troca a escala.
@Service
class NetWorthService {

  // A data do usuário é a de São Paulo: a cotação das 23:30 de lá ainda vale para o dia.
  static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  // 9007199254740991 centavos em reais. Comparado com compareTo: a escala não conta.
  private static final BigDecimal LIMIT = new BigDecimal("90071992547409.91");

  private final CryptoQueries queries;
  private final FixedIncomeService fixedIncome;

  NetWorthService(CryptoQueries queries, FixedIncomeService fixedIncome) {
    this.queries = queries;
    this.fixedIncome = fixedIncome;
  }

  // Valores já em centavos. Nulos sem cotação, e o da outra moeda também nulo sem dólar. Custo da
  // posição, custo médio e realizado vêm do custo médio ponderado (ADR-042). O ganho é o valor
  // menos o custo da posição, os dois já em centavos: a conta que a tela mostra fecha.
  record ValuedPosition(
      CryptoQueries.Position position,
      BigDecimal valueUsd,
      BigDecimal valueBrl,
      BigDecimal costUsd,
      BigDecimal costBrl,
      BigDecimal averageCostUsd,
      BigDecimal basisUsd,
      BigDecimal basisBrl,
      BigDecimal gainUsd,
      BigDecimal gainBrl,
      BigDecimal realizedUsd,
      BigDecimal realizedBrl,
      boolean outOfRange) {

    // Posição cujo valor não cabe no inteiro seguro do JSON: a quantidade fica, todo valor some
    // (ADR-058).
    private static ValuedPosition withoutValue(CryptoQueries.Position position) {
      return new ValuedPosition(
          position, null, null, null, null, null, null, null, null, null, null, null, true);
    }

    private List<BigDecimal> amounts() {
      return Arrays.asList(
          valueUsd,
          valueBrl,
          costUsd,
          costBrl,
          basisUsd,
          basisBrl,
          gainUsd,
          gainBrl,
          realizedUsd,
          realizedBrl);
    }
  }

  private record PositionKey(UUID accountId, UUID assetId) {}

  private record Bases(CostBasis usd, CostBasis brl) {}

  record Positions(CryptoQueries.Dollar dollar, List<ValuedPosition> positions) {}

  static final String OUT_OF_RANGE_REASON = "fora do limite";
  static final String UNQUOTED_REASON = "sem cotação";

  // reason diz por que a posição ficou fora do total: sem cotação (ou sem dólar) ou fora do limite.
  record Unquoted(CryptoQueries.Position position, String reason) {}

  // cryptoContribution é compras menos vendas, em reais, das posições que entram em crypto, e
  // cryptoAppreciation o resto do valor delas. Os dois nulos sem a PTAX de algum movimento.
  record NetWorth(
      BigDecimal accounts,
      BigDecimal crypto,
      BigDecimal cryptoContribution,
      BigDecimal cryptoAppreciation,
      BigDecimal fixedIncome,
      BigDecimal total,
      List<Unquoted> unquoted,
      List<FixedIncomeService.Excluded> fixedIncomeExcluded) {}

  // dollar é a PTAX da data (nula se não há). Posição com valor fora da faixa do JSON vem sem
  // valor, e não derruba a lista (ADR-058).
  Positions positions(UUID userId, LocalDate date) {
    CryptoQueries.Dollar dollar = queries.dollarAt(date).orElse(null);
    BigDecimal rate = dollar == null ? null : dollar.value();
    List<ValuedPosition> valued = new ArrayList<>();
    Map<PositionKey, Bases> bases = bases(queries.movementsUntil(userId, date));
    for (CryptoQueries.Position position : queries.positions(userId, date, quoteLimit(date))) {
      BigDecimal usd = null;
      BigDecimal brl = null;
      if (position.price() != null) {
        BigDecimal value = position.quantity().multiply(position.price());
        boolean inUsd = "USD".equals(position.priceCurrency());
        usd = inUsd ? value : convert(value, rate, false);
        brl = inUsd ? convert(value, rate, true) : value;
      }
      Bases basis = bases.get(new PositionKey(position.accountId(), position.assetId()));
      CostBasis.Basis basisUsd = basis.usd().result().orElse(null);
      CostBasis.Basis basisBrl = basis.brl().result().orElse(null);
      BigDecimal valueUsd = cents(usd);
      BigDecimal valueBrl = cents(brl);
      BigDecimal costOfPositionUsd = basisUsd == null ? null : cents(basisUsd.cost());
      BigDecimal costOfPositionBrl = basisBrl == null ? null : cents(basisBrl.cost());
      ValuedPosition item =
          new ValuedPosition(
              position,
              valueUsd,
              valueBrl,
              cents(position.costUsd()),
              cents(position.costBrl()),
              // Oito casas, as mesmas da cotação, para comparar uma com a outra na tela.
              basisUsd == null || basisUsd.averageCost() == null
                  ? null
                  : basisUsd.averageCost().setScale(8, RoundingMode.HALF_EVEN),
              costOfPositionUsd,
              costOfPositionBrl,
              difference(valueUsd, costOfPositionUsd),
              difference(valueBrl, costOfPositionBrl),
              basisUsd == null ? null : cents(basisUsd.realized()),
              basisBrl == null ? null : cents(basisBrl.realized()),
              false);
      valued.add(
          item.amounts().stream().allMatch(NetWorthService::fits)
              ? item
              : ValuedPosition.withoutValue(position));
    }
    return new Positions(dollar, valued);
  }

  Optional<NetWorth> netWorth(UUID userId, LocalDate date) {
    Positions positions = positions(userId, date);
    // Sem cotação, sem o dólar que leva a cotação em USD para reais, ou com o valor fora da faixa,
    // a posição fica de fora.
    BigDecimal crypto = BigDecimal.ZERO;
    BigDecimal contribution = BigDecimal.ZERO;
    List<Unquoted> unquoted = new ArrayList<>();
    for (ValuedPosition valued : positions.positions()) {
      // Ativo não confiável (spam de uma leitura de carteira) não entra em nada até ser promovido.
      if (!valued.position().trusted()) {
        continue;
      }
      if (valued.valueBrl() == null) {
        unquoted.add(
            new Unquoted(
                valued.position(), valued.outOfRange() ? OUT_OF_RANGE_REASON : UNQUOTED_REASON));
      } else {
        crypto = crypto.add(valued.valueBrl());
        contribution =
            contribution == null || valued.costBrl() == null
                ? null
                : contribution.add(valued.costBrl());
      }
    }
    BigDecimal appreciation = difference(crypto, contribution);
    BigDecimal accounts = queries.accountsBalance(userId, date);
    // O líquido de cada título: o que se receberia resgatando (ADR-037).
    Optional<FixedIncomeService.NetTotal> titles = fixedIncome.netTotal(userId, date);
    if (titles.isEmpty()) {
      return Optional.empty();
    }
    BigDecimal total = accounts.add(crypto).add(titles.get().total());
    if (!fits(accounts)
        || !fits(crypto)
        || !fits(contribution)
        || !fits(appreciation)
        || !fits(total)) {
      return Optional.empty();
    }
    return Optional.of(
        new NetWorth(
            accounts,
            crypto,
            contribution,
            appreciation,
            titles.get().total(),
            total,
            unquoted,
            titles.get().excluded()));
  }

  // Patrimônio no fim de cada mês, até hoje (ADR-047). O mês corrente vale hoje, e mês depois
  // dele fica de fora: patrimônio futuro seria só o previsto. Vazio se algum mês sai da faixa.
  Optional<List<MonthEnd>> monthly(UUID userId, YearMonth last, int count, LocalDate today) {
    List<MonthEnd> months = new ArrayList<>();
    for (YearMonth month = last.minusMonths(count - 1L);
        !month.isAfter(last) && !month.isAfter(YearMonth.from(today));
        month = month.plusMonths(1)) {
      LocalDate end = month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
      Optional<NetWorth> found = netWorth(userId, end);
      if (found.isEmpty()) {
        return Optional.empty();
      }
      months.add(new MonthEnd(month, end, found.get()));
    }
    return Optional.of(months);
  }

  record MonthEnd(YearMonth month, LocalDate date, NetWorth netWorth) {}

  // Um custo médio por posição e moeda, com os movimentos já na ordem certa. O movimento em
  // reais sem a PTAX da data deixa o lado em dólar sem custo, e o contrário também.
  private static Map<PositionKey, Bases> bases(List<CryptoQueries.Movement> movements) {
    Map<PositionKey, Bases> bases = new HashMap<>();
    for (CryptoQueries.Movement movement : movements) {
      Bases basis =
          bases.computeIfAbsent(
              new PositionKey(movement.accountId(), movement.assetId()),
              key -> new Bases(new CostBasis(), new CostBasis()));
      // Sem custo (entrada ou saída sincronizada), o valor nulo deixa o custo médio nulo.
      if (movement.cost() == null) {
        basis.usd().add(movement.type(), movement.quantity(), null);
        basis.brl().add(movement.type(), movement.quantity(), null);
        continue;
      }
      boolean inUsd = "USD".equals(movement.currency());
      basis
          .usd()
          .add(
              movement.type(),
              movement.quantity(),
              inUsd ? movement.cost() : convert(movement.cost(), movement.dollar(), false));
      basis
          .brl()
          .add(
              movement.type(),
              movement.quantity(),
              inUsd ? convert(movement.cost(), movement.dollar(), true) : movement.cost());
    }
    return bases;
  }

  private static BigDecimal difference(BigDecimal value, BigDecimal cost) {
    return value == null || cost == null ? null : value.subtract(cost);
  }

  // reais = dólares x PTAX; dólares = reais / PTAX. Sem PTAX não há conversão. Fica exato: a
  // divisão
  // usa 34 dígitos, e só cents arredonda.
  private static BigDecimal convert(BigDecimal value, BigDecimal rate, boolean toBrl) {
    if (rate == null) {
      return null;
    }
    return toBrl ? value.multiply(rate) : value.divide(rate, MathContext.DECIMAL128);
  }

  // Arredonda uma vez, aqui, cada moeda a partir do valor cheio. HALF_EVEN porque numa avaliação
  // não há direção certa para o meio centavo, e o arredondamento bancário não acumula viés numa
  // soma de várias posições (ADR-035, ADR-038).
  private static BigDecimal cents(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.HALF_EVEN);
  }

  // A cotação vale até o fim do dia em São Paulo: tudo antes do começo do dia seguinte.
  private static Instant quoteLimit(LocalDate date) {
    return date.plusDays(1).atStartOfDay(ZONE).toInstant();
  }

  private static boolean fits(BigDecimal amount) {
    return amount == null || amount.abs().compareTo(LIMIT) <= 0;
  }
}
