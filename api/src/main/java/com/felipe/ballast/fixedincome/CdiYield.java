package com.felipe.ballast.fixedincome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.NavigableMap;
import org.springframework.stereotype.Component;

// Rendimento estimado de saldo parado a um percentual do CDI (ADR-061). Usa o CDI guardado e,
// depois
// do último publicado, o último valor conhecido nos dias úteis.
@Component
public class CdiYield {

  // Janela antes de from para sempre achar um CDI publicado de referência.
  private static final int LOOKBACK_DAYS = 15;

  private final IndexQueries indices;

  CdiYield(IndexQueries indices) {
    this.indices = indices;
  }

  public NavigableMap<LocalDate, BigDecimal> load(LocalDate from) {
    return indices.load(IndexSeries.CDI, from.minusDays(LOOKBACK_DAYS));
  }

  // Quanto o saldo rende em [from, to). DOWN: a estimativa nunca promete mais do que o banco paga.
  // Saldo zero ou negativo não rende. Sem CDI guardado devolve zero.
  public static BigDecimal estimate(
      NavigableMap<LocalDate, BigDecimal> cdi,
      BigDecimal balance,
      BigDecimal percentOfCdi,
      LocalDate from,
      LocalDate to) {
    if (balance.signum() <= 0 || !from.isBefore(to) || cdi.isEmpty()) {
      return BigDecimal.ZERO.setScale(2);
    }
    BigDecimal factor = FixedIncomeMath.cdiFactor(cdi, from, to, percentOfCdi);
    return balance.multiply(factor.subtract(BigDecimal.ONE)).setScale(2, RoundingMode.DOWN);
  }
}
