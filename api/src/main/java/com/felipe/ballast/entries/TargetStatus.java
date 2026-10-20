package com.felipe.ballast.entries;

import java.util.Optional;

// Os dois destinos que o PATCH aceita. "previsto" não volta: cancelar e confirmar são definitivos.
enum TargetStatus {
  REALIZED("realizado"),
  CANCELLED("cancelado");

  private final String value;

  TargetStatus(String value) {
    this.value = value;
  }

  String value() {
    return value;
  }

  static Optional<TargetStatus> fromValue(String value) {
    for (TargetStatus status : values()) {
      if (status.value.equals(value)) {
        return Optional.of(status);
      }
    }
    return Optional.empty();
  }
}
