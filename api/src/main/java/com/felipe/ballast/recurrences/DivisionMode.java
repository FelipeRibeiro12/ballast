package com.felipe.ballast.recurrences;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

// Todas as parcelas de um template usam o mesmo modo (ADR-027).
public enum DivisionMode {
  PERCENT("percentual"),
  AMOUNT("valor");

  private final String value;

  DivisionMode(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  // Valor desconhecido vira null e o @NotNull da requisição responde a mensagem fixa.
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  static DivisionMode fromJson(String value) {
    for (DivisionMode mode : values()) {
      if (mode.value.equals(value)) {
        return mode;
      }
    }
    return null;
  }
}
