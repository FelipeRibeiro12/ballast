package com.felipe.ballast.limits;

// Vira 409 no ApiExceptionHandler. Lançada de dentro da transação, que desfaz tudo.
public class LimitReachedException extends RuntimeException {

  public LimitReachedException(UserLimit limit) {
    super("limite de " + limit.label + " atingido");
  }
}
