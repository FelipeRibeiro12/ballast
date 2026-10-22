package com.felipe.ballast.fixedincome;

import com.fasterxml.jackson.annotation.JsonValue;

// Mesmo valor do CHECK titulo_renda_fixa_tipo_valido (V14). LCI e LCA são isentas de IR.
public enum FixedIncomeType {
  LCI("lci", false),
  LCA("lca", false),
  CDB("cdb", true);

  private final String value;
  private final boolean taxed;

  FixedIncomeType(String value, boolean taxed) {
    this.value = value;
    this.taxed = taxed;
  }

  @JsonValue
  public String value() {
    return value;
  }

  boolean taxed() {
    return taxed;
  }

  static FixedIncomeType fromValue(String value) {
    for (FixedIncomeType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    throw new IllegalStateException("tipo de título desconhecido no banco");
  }
}
