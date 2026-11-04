package com.felipe.ballast.crypto;

import com.felipe.ballast.fixedincome.FixedIncomeService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
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
  private final Clock clock;

  NetWorthService(CryptoQueries queries, FixedIncomeService fixedIncome, Clock clock) {
    this.queries = queries;
    this.fixedIncome = fixedIncome;
    this.clock = clock;
  }

  // Valores já em centavos. Nulos sem cotação, e o da outra moeda também nulo sem dólar. Custo da
  // posição, custo médio e realizado vêm do custo médio ponderado (ADR-042). O ganho é o valor
  // menos o custo da posição, os dois já em centavos: a conta que a tela mostra fecha.
  record ValuedPosition(
      CryptoQueries.Position position,
      BigDecimal valueUsd,
      BigDecimal valueBrl,
      BigDecimal fullUsd,
      BigDecimal fullBrl,
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
          position, null, null, null, null, null, null, null, null, null, null, null, null, null,
          true);
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

  // Valor de uma conta cripto: soma dos valores cheios das posições, arredondada uma vez. Cada
  // moeda é nula se alguma posição da conta não tem o valor nela.
  record AccountValue(UUID accountId, BigDecimal valueBrl, BigDecimal valueUsd) {}

  record Positions(
      CryptoQueries.Dollar dollar, List<ValuedPosition> positions, List<AccountValue> accounts) {}

  static final String OUT_OF_RANGE_REASON = "fora do limite";
  static final String UNQUOTED_REASON = "sem cotação";

  // reason diz por que a posição ficou fora do total: sem cotação (ou sem dólar) ou fora do limite.
  record Unquoted(CryptoQueries.Position position, String reason) {}

  // cryptoUsd soma o valor em dólar das posições com cotação; nulo se alguma tem cotação em reais e
  // nenhum dólar para voltar a dólar. cryptoContribution é compras menos vendas, em reais, das
  // posições que entram em crypto, e
  // cryptoAppreciation o resto do valor delas. Os dois nulos sem a cotação do dólar de algum
  // movimento.
  // defi e defiUsd são o DeFi das carteiras em reais e em dólar, só hoje (ADR-082); zero nas outras
  // datas, e nulos os dois sem cotação do dólar. Já estão em total quando valem.
  record NetWorth(
      BigDecimal accounts,
      BigDecimal crypto,
      BigDecimal cryptoUsd,
      BigDecimal cryptoContribution,
      BigDecimal cryptoAppreciation,
      BigDecimal fixedIncome,
      BigDecimal defi,
      BigDecimal defiUsd,
      BigDecimal total,
      List<CryptoQueries.SavingsPot> savingsPots,
      List<Unquoted> unquoted,
      List<FixedIncomeService.Excluded> fixedIncomeExcluded) {}

  // dollar é o dólar da data (USDC, ou PTAX sem ele; nulo se não há). Posição com valor fora da
  // faixa do JSON vem sem
  // valor, e não derruba a lista (ADR-058).
  Positions positions(UUID userId, LocalDate date) {
    CryptoQueries.Dollar dollar = queries.dollarAt(date, quoteLimit(date)).orElse(null);
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
              usd,
              brl,
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
    return new Positions(dollar, valued, accountValues(userId, valued));
  }

  // Só ativo confiável entra, como no patrimônio. Posição sem cotação, sem o dólar ou fora do
  // limite
  // deixa nula a moeda que lhe falta; conta sem posição vale zero.
  private List<AccountValue> accountValues(UUID userId, List<ValuedPosition> valued) {
    List<AccountValue> accounts = new ArrayList<>();
    for (UUID accountId : queries.activeCryptoAccountIds(userId)) {
      BigDecimal brl = BigDecimal.ZERO;
      BigDecimal usd = BigDecimal.ZERO;
      for (ValuedPosition item : valued) {
        if (!item.position().accountId().equals(accountId) || !item.position().trusted()) {
          continue;
        }
        brl = brl == null || item.fullBrl() == null ? null : brl.add(item.fullBrl());
        usd = usd == null || item.fullUsd() == null ? null : usd.add(item.fullUsd());
      }
      BigDecimal valueBrl = cents(brl);
      BigDecimal valueUsd = cents(usd);
      accounts.add(
          new AccountValue(
              accountId, fits(valueBrl) ? valueBrl : null, fits(valueUsd) ? valueUsd : null));
    }
    return accounts;
  }

  Optional<NetWorth> netWorth(UUID userId, LocalDate date) {
    return netWorth(userId, date, true);
  }

  // withDefi falso é o histórico mensal: o retrato do DeFi não tem histórico, então nenhum ponto
  // da série o leva (ADR-082).
  private Optional<NetWorth> netWorth(UUID userId, LocalDate date, boolean withDefi) {
    Positions positions = positions(userId, date);
    // Sem cotação, sem o dólar que leva a cotação em USD para reais, ou com o valor fora da faixa,
    // a posição fica de fora.
    BigDecimal crypto = BigDecimal.ZERO;
    BigDecimal cryptoUsd = BigDecimal.ZERO;
    BigDecimal contribution = BigDecimal.ZERO;
    List<Unquoted> unquoted = new ArrayList<>();
    for (ValuedPosition valued : positions.positions()) {
      // Ativo não confiável (spam de uma leitura de carteira) não entra em nada até ser promovido.
      if (!valued.position().trusted() || isSyncedDust(valued)) {
        continue;
      }
      if (valued.valueUsd() != null) {
        cryptoUsd = cryptoUsd == null ? null : cryptoUsd.add(valued.valueUsd());
      } else if (valued.position().price() != null && !valued.outOfRange()) {
        cryptoUsd = null;
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
    BigDecimal defiUsd = null;
    BigDecimal defi = BigDecimal.ZERO;
    boolean defiCounts = withDefi && date.equals(today());
    if (!defiCounts) {
      defiUsd = BigDecimal.ZERO;
    } else if (positions.dollar() != null) {
      BigDecimal usd = BigDecimal.valueOf(queries.defiUsdCents(userId), 2);
      defiUsd = usd;
      // Uma vez, do valor cheio em dólar, como as posições (ADR-077).
      defi = cents(usd.multiply(positions.dollar().value()));
    }
    BigDecimal total = accounts.add(crypto).add(titles.get().total()).add(defi);
    // O cofrinho marcado já está em accounts; aqui só se separa para a tela, sem somar de novo.
    List<CryptoQueries.SavingsPot> savingsPots = queries.investedSavingsPots(userId, date);
    BigDecimal savingsTotal =
        savingsPots.stream()
            .map(CryptoQueries.SavingsPot::balance)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (!fits(savingsTotal) || savingsPots.stream().anyMatch(pot -> !fits(pot.balance()))) {
      return Optional.empty();
    }
    if (!fits(accounts)
        || !fits(crypto)
        || !fits(cryptoUsd)
        || !fits(contribution)
        || !fits(appreciation)
        || !fits(defi)
        || !fits(defiUsd)
        || !fits(total)) {
      return Optional.empty();
    }
    return Optional.of(
        new NetWorth(
            accounts,
            crypto,
            cryptoUsd,
            contribution,
            appreciation,
            titles.get().total(),
            defi,
            defiUsd,
            total,
            savingsPots,
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
      Optional<NetWorth> found = netWorth(userId, end, false);
      if (found.isEmpty()) {
        return Optional.empty();
      }
      months.add(new MonthEnd(month, end, found.get()));
    }
    return Optional.of(months);
  }

  record MonthEnd(YearMonth month, LocalDate date, NetWorth netWorth) {}

  private LocalDate today() {
    return clock.instant().atZone(ZONE).toLocalDate();
  }

  // Saldo que só veio de leitura de carteira e vale menos de US$ 0,50 é poeira e sai do total.
  // Posição com qualquer movimento digitado nunca é cortada. Sem valor em dólar não dá para medir.
  private static boolean isSyncedDust(ValuedPosition valued) {
    return valued.position().onlySynced()
        && valued.fullUsd() != null
        && DustCutoff.isDust(valued.fullUsd());
  }

  // Um custo médio por posição e moeda, com os movimentos já na ordem certa. O movimento em
  // reais sem o dólar da data deixa o lado em dólar sem custo, e o contrário também.
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

  // reais = dólares x cotação; dólares = reais / cotação. Sem cotação não há conversão. Fica exato:
  // a divisão usa 34 dígitos, e só cents arredonda.
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
