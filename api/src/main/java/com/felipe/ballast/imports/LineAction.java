package com.felipe.ballast.imports;

import com.fasterxml.jackson.annotation.JsonValue;

// O valor do JSON é o do CHECK importacao_linha_decisao_valida.
public enum LineAction {
  IMPORT("importar"),
  IGNORE("ignorar"),
  RECONCILE("conciliar");

  private final String value;

  LineAction(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }
}
