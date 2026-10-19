package com.felipe.ballast.categories;

import com.fasterxml.jackson.annotation.JsonValue;

// O valor do JSON é o mesmo do CHECK categoria_tipo_valido.
public enum CategoryType {
  INCOME("receita"),
  EXPENSE("despesa");

  private final String value;

  CategoryType(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  static CategoryType fromValue(String value) {
    for (CategoryType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    throw new IllegalStateException("tipo de categoria desconhecido no banco");
  }
}
