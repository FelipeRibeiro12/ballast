package com.felipe.ballast.entries;

import com.fasterxml.jackson.annotation.JsonValue;

// O valor do JSON é o mesmo do CHECK lancamento_tipo_valido. Transferência só nasce pela rota
// de transferência (ADR-025): o corpo de /lancamentos recusa esse valor.
public enum EntryType {
  INCOME("receita"),
  EXPENSE("despesa"),
  TRANSFER("transferencia");

  private final String value;

  EntryType(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  static EntryType fromValue(String value) {
    for (EntryType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    throw new IllegalStateException("tipo de lançamento desconhecido no banco");
  }
}
