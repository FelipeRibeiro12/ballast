package com.felipe.ballast.fixedincome;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

// Contas da renda fixa, sem banco nem relógio (ADR-037). Os fatores ficam com 34 dígitos e só o
// valor final vira centavos, uma vez, em quem chama. Nada passa por double.
final class FixedIncomeMath {

  static final MathContext CONTEXT = MathContext.DECIMAL128;

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final int BUSINESS_DAYS_PER_YEAR = 252;

  // IOF regressivo do dia 1 ao 29, em % do rendimento; do dia 30 em diante é zero.
  private static final int[] IOF_PERCENT = {
    96, 93, 90, 86, 83, 80, 76, 73, 70, 66, 63, 60, 56, 53, 50, 46, 43, 40, 36, 33, 30, 26, 23, 20,
    16, 13, 10, 6, 3
  };

  private FixedIncomeMath() {}

  record Taxes(BigDecimal iof, BigDecimal incomeTax, BigDecimal net) {}

  // Raiz n por Newton: y = ((n - 1) y + x / y^(n - 1)) / n. Converge rápido partindo de 1, que é
  // onde vivem os fatores de juros.
  static BigDecimal root(BigDecimal value, int n) {
    if (n == 1 || value.compareTo(BigDecimal.ONE) == 0) {
      return value;
    }
    BigDecimal degree = BigDecimal.valueOf(n);
    BigDecimal guess = BigDecimal.ONE;
    for (int i = 0; i < 200; i++) {
      BigDecimal next =
          guess
              .multiply(BigDecimal.valueOf(n - 1L), CONTEXT)
              .add(value.divide(guess.pow(n - 1, CONTEXT), CONTEXT), CONTEXT)
              .divide(degree, CONTEXT);
      if (next.compareTo(guess) == 0) {
        return next;
      }
      guess = next;
    }
    return guess;
  }

  // (1 + taxa)^(du/252), pela raiz 252 elevada a du.
  static BigDecimal prefixedFactor(BigDecimal yearlyPercent, int businessDays) {
    if (businessDays == 0) {
      return BigDecimal.ONE;
    }
    return yearlyRoot(yearlyPercent).pow(businessDays, CONTEXT);
  }

  // A raiz 252 pelo Newton é a parte cara e só depende da taxa: cada título e cada leitura da lista
  // a refaziam. Com taxa até 1000 e 4 casas há milhões de chaves possíveis, então o mapa tem teto:
  // cheio, calcula sem guardar.
  static final int ROOT_CACHE_LIMIT = 1000;

  private static final ConcurrentHashMap<BigDecimal, BigDecimal> ROOTS = new ConcurrentHashMap<>();

  private static BigDecimal yearlyRoot(BigDecimal yearlyPercent) {
    // 12.5 e 12.50 são a mesma taxa; a chave sem zeros à direita evita duas entradas.
    BigDecimal key = yearlyPercent.stripTrailingZeros();
    BigDecimal cached = ROOTS.get(key);
    if (cached != null) {
      return cached;
    }
    BigDecimal yearly = BigDecimal.ONE.add(yearlyPercent.divide(HUNDRED, CONTEXT), CONTEXT);
    BigDecimal root = root(yearly, BUSINESS_DAYS_PER_YEAR);
    if (ROOTS.size() < ROOT_CACHE_LIMIT) {
      ROOTS.putIfAbsent(key, root);
    }
    return root;
  }

  static int rootCacheSize() {
    return ROOTS.size();
  }

  static void clearRootCache() {
    ROOTS.clear();
  }

  // Π (1 + CDI_d x %CDI) nos dias úteis em [from, to). Dia útil sem CDI publicado ainda (depois do
  // último guardado) conta de segunda a sexta com o último CDI conhecido.
  static BigDecimal cdiFactor(
      NavigableMap<LocalDate, BigDecimal> cdi,
      LocalDate from,
      LocalDate to,
      BigDecimal percentOfCdi) {
    BigDecimal share = percentOfCdi.divide(HUNDRED, CONTEXT);
    BigDecimal factor = BigDecimal.ONE;
    for (BigDecimal daily : dailyRates(cdi, from, to)) {
      BigDecimal rate = daily.divide(HUNDRED, CONTEXT).multiply(share, CONTEXT);
      factor = factor.multiply(BigDecimal.ONE.add(rate, CONTEXT), CONTEXT);
    }
    return factor;
  }

  static int businessDays(NavigableMap<LocalDate, BigDecimal> cdi, LocalDate from, LocalDate to) {
    return dailyRates(cdi, from, to).size();
  }

  // Um CDI por dia útil em [from, to): os publicados e, depois do último, os dias de semana com o
  // último valor. Sem nenhum CDI guardado não há como saber nem o dia útil nem a taxa.
  private static List<BigDecimal> dailyRates(
      NavigableMap<LocalDate, BigDecimal> cdi, LocalDate from, LocalDate to) {
    List<BigDecimal> rates = new ArrayList<>(cdi.subMap(from, true, to, false).values());
    if (cdi.isEmpty()) {
      return rates;
    }
    LocalDate last = cdi.lastKey();
    BigDecimal lastRate = cdi.lastEntry().getValue();
    LocalDate day = last.plusDays(1).isAfter(from) ? last.plusDays(1) : from;
    for (; day.isBefore(to); day = day.plusDays(1)) {
      DayOfWeek weekday = day.getDayOfWeek();
      if (weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY) {
        rates.add(lastRate);
      }
    }
    return rates;
  }

  // Π (1 + IPCA_m) com o mês parcial na proporção dos dias corridos, exponencial. ipca tem a data
  // do
  // primeiro dia do mês, como o SGS publica. Mês sem IPCA publicado usa o último.
  static BigDecimal ipcaFactor(Map<LocalDate, BigDecimal> ipca, LocalDate from, LocalDate to) {
    NavigableMap<LocalDate, BigDecimal> byMonth = new TreeMap<>(ipca);
    BigDecimal factor = BigDecimal.ONE;
    LocalDate start = from;
    while (start.isBefore(to)) {
      YearMonth month = YearMonth.from(start);
      LocalDate monthEnd = month.atEndOfMonth().plusDays(1);
      LocalDate end = monthEnd.isBefore(to) ? monthEnd : to;
      Map.Entry<LocalDate, BigDecimal> published = byMonth.floorEntry(month.atDay(1));
      if (published != null) {
        BigDecimal monthly =
            BigDecimal.ONE.add(published.getValue().divide(HUNDRED, CONTEXT), CONTEXT);
        int days = (int) ChronoUnit.DAYS.between(start, end);
        int length = month.lengthOfMonth();
        BigDecimal part = days == length ? monthly : root(monthly, length).pow(days, CONTEXT);
        factor = factor.multiply(part, CONTEXT);
      }
      start = end;
    }
    return factor;
  }

  static BigDecimal iofRate(long days) {
    if (days <= 0) {
      return BigDecimal.ONE;
    }
    if (days >= 30) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(IOF_PERCENT[(int) days - 1], 2);
  }

  static BigDecimal incomeTaxRate(long days) {
    if (days <= 180) {
      return new BigDecimal("0.225");
    }
    if (days <= 360) {
      return new BigDecimal("0.20");
    }
    if (days <= 720) {
      return new BigDecimal("0.175");
    }
    return new BigDecimal("0.15");
  }

  // IOF sobre o rendimento, IR sobre o rendimento menos o IOF; cada imposto vira centavos uma vez
  // com HALF_EVEN, e o líquido é o bruto menos os dois (ADR-037). gross já vem em centavos.
  static Taxes taxes(FixedIncomeType type, BigDecimal invested, BigDecimal gross, long days) {
    BigDecimal gain = gross.subtract(invested);
    if (!type.taxed() || gain.signum() <= 0) {
      return new Taxes(BigDecimal.ZERO, BigDecimal.ZERO, gross);
    }
    BigDecimal iof = gain.multiply(iofRate(days)).setScale(2, RoundingMode.HALF_EVEN);
    BigDecimal incomeTax =
        gain.subtract(iof).multiply(incomeTaxRate(days)).setScale(2, RoundingMode.HALF_EVEN);
    return new Taxes(iof, incomeTax, gross.subtract(iof).subtract(incomeTax));
  }
}
