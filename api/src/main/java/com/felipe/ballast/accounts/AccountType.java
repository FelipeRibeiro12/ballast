package com.felipe.ballast.accounts;

import com.fasterxml.jackson.annotation.JsonValue;

// O valor do JSON é o mesmo do CHECK conta_tipo_valido. Tipo novo entra aqui junto com a
// migration que alarga o CHECK.
public enum AccountType {
  CHECKING("corrente"),
  SAVINGS("poupanca"),
  CASH("dinheiro"),
  CREDIT_CARD("cartao_credito");

  private final String value;

  AccountType(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  static AccountType fromValue(String value) {
    for (AccountType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    throw new IllegalStateException("tipo de conta desconhecido no banco");
  }
}
