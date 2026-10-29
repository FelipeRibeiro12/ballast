package com.felipe.ballast.crypto;

import com.fasterxml.jackson.annotation.JsonValue;

// Mesmo valor do CHECK movimento_cripto_tipo_valido (V13, alargado na V36). Entrada e saída só
// nascem da leitura de uma carteira observada (ADR-071); quem digita usa compra e venda.
public enum MovementType {
  BUY("compra"),
  SELL("venda"),
  ENTRY("entrada"),
  EXIT("saida");

  private final String value;

  MovementType(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  // Soma na posição: compra e entrada. Venda e saída subtraem.
  boolean adds() {
    return this == BUY || this == ENTRY;
  }

  // Só compra e venda podem ser digitadas.
  boolean typed() {
    return this == BUY || this == SELL;
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
