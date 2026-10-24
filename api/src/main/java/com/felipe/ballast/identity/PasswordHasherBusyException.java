package com.felipe.ballast.identity;

// Todas as vagas do argon2 ocupadas além da espera. Vira 503 no ApiExceptionHandler.
public class PasswordHasherBusyException extends RuntimeException {

  PasswordHasherBusyException() {
    super("argon2 sem vaga livre");
  }
}
