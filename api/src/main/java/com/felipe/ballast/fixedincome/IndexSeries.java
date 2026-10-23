package com.felipe.ballast.fixedincome;

import java.math.BigDecimal;
import java.time.LocalDate;

// Séries do SGS do Banco Central gravadas em indice (V14). O valor é o mesmo do CHECK
// indice_serie_valida; sgsCode é o número da série na API.
public enum IndexSeries {
  // % ao dia, um valor por dia útil, desde 04/03/1986. A hiperinflação de 1989-1990 passou de 2%
  // ao dia; 10% é folga, acima disso o dado veio errado.
  CDI("cdi", 12, LocalDate.of(1986, 3, 4), BigDecimal.ZERO, new BigDecimal("10")),
  // % ao mês, datado do primeiro dia do mês, desde 1980. Março de 1990 passou de 80%.
  IPCA("ipca", 433, LocalDate.of(1980, 1, 1), new BigDecimal("-5"), new BigDecimal("100"));

  private final String value;
  private final int sgsCode;
  private final LocalDate firstPublished;
  private final BigDecimal min;
  private final BigDecimal max;

  IndexSeries(String value, int sgsCode, LocalDate firstPublished, BigDecimal min, BigDecimal max) {
    this.value = value;
    this.sgsCode = sgsCode;
    this.firstPublished = firstPublished;
    this.min = min;
    this.max = max;
  }

  // Antes disso o Banco Central não tem a série: pedir só volta vazio (auditoria do PR #22).
  LocalDate firstPublished() {
    return firstPublished;
  }

  // Valor fora da faixa não é gravado: ficaria para sempre e estragaria o cálculo de todos.
  boolean plausible(BigDecimal candidate) {
    return candidate.compareTo(min) >= 0 && candidate.compareTo(max) <= 0;
  }

  public String value() {
    return value;
  }

  int sgsCode() {
    return sgsCode;
  }
}
