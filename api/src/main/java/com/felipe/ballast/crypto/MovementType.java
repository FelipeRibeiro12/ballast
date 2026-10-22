package com.felipe.ballast.crypto;

import com.fasterxml.jackson.annotation.JsonValue;

// Mesmo valor do CHECK movimento_cripto_tipo_valido (V13).
public enum MovementType {
  BUY("compra"),
  SELL("venda");

  private final String value;

  MovementType(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  static MovementType fromValue(String value) {
    for (MovementType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    throw new IllegalStateException("tipo de movimento desconhecido no banco");
  }
}
