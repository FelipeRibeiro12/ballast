package com.felipe.ballast.entries;

import com.fasterxml.jackson.annotation.JsonValue;

// O valor do JSON é o mesmo do CHECK lancamento_tipo_valido. A F4 acrescenta transferencia aqui
// junto com a migration que alarga o CHECK.
public enum EntryType {
  INCOME("receita"),
  EXPENSE("despesa");

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
