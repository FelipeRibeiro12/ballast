package com.felipe.ballast.fixedincome;

import com.fasterxml.jackson.annotation.JsonValue;

// Mesmo valor do CHECK titulo_renda_fixa_indexador_valido (V14). O sentido da taxa muda com ele:
// % a.a. no prefixado, % do CDI no cdi, % a.a. acima do IPCA no ipca (ADR-037).
public enum Indexer {
  PREFIXED("prefixado"),
  CDI("cdi"),
  IPCA("ipca");

  private final String value;

  Indexer(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  static Indexer fromValue(String value) {
    for (Indexer indexer : values()) {
      if (indexer.value.equals(value)) {
        return indexer;
      }
    }
    throw new IllegalStateException("indexador desconhecido no banco");
  }
}
