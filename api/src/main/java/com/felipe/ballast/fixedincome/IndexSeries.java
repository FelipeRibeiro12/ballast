package com.felipe.ballast.fixedincome;

// Séries do SGS do Banco Central gravadas em indice (V14). O valor é o mesmo do CHECK
// indice_serie_valida; sgsCode é o número da série na API.
public enum IndexSeries {
  // % ao dia, um valor por dia útil.
  CDI("cdi", 12),
  // % ao mês, datado do primeiro dia do mês.
  IPCA("ipca", 433);

  private final String value;
  private final int sgsCode;

  IndexSeries(String value, int sgsCode) {
    this.value = value;
    this.sgsCode = sgsCode;
  }

  public String value() {
    return value;
  }

  int sgsCode() {
    return sgsCode;
  }
}
