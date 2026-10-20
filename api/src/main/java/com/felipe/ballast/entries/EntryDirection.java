package com.felipe.ballast.entries;

import com.fasterxml.jackson.annotation.JsonValue;

// Lado da perna numa transferência. Vem da ligação, não de coluna do lançamento (ADR-025).
public enum EntryDirection {
  OUT("saida"),
  IN("entrada");

  private final String value;

  EntryDirection(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  static EntryDirection fromValue(String value) {
    if (value == null) {
      return null;
    }
    for (EntryDirection direction : values()) {
      if (direction.value.equals(value)) {
        return direction;
      }
    }
    throw new IllegalStateException("direção de transferência desconhecida no banco");
  }
}
