package com.felipe.ballast.recurrences;

import com.fasterxml.jackson.annotation.JsonValue;

// O valor do JSON é o mesmo do CHECK regra_frequencia_valida.
public enum RecurrenceFrequency {
  DAILY("diaria"),
  WEEKLY("semanal"),
  MONTHLY("mensal"),
  ANNUAL("anual");

  private final String value;

  RecurrenceFrequency(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  static RecurrenceFrequency fromValue(String value) {
    for (RecurrenceFrequency frequency : values()) {
      if (frequency.value.equals(value)) {
        return frequency;
      }
    }
    throw new IllegalStateException("frequência desconhecida no banco");
  }
}
