package com.felipe.ballast.identity;

import java.time.Duration;
import org.springframework.http.HttpStatus;

// Recusa do MFA com o status e a mensagem fixa que vão para o cliente. O MfaController traduz.
final class MfaError extends RuntimeException {

  private final HttpStatus status;
  private final Duration retryAfter;

  private MfaError(HttpStatus status, String message, Duration retryAfter) {
    super(message, null, false, false);
    this.status = status;
    this.retryAfter = retryAfter;
  }

  static MfaError of(HttpStatus status, String message) {
    return new MfaError(status, message, null);
  }

  static MfaError delayed(Duration retryAfter) {
    return new MfaError(
        HttpStatus.TOO_MANY_REQUESTS, "muitas tentativas, tente mais tarde", retryAfter);
  }

  HttpStatus status() {
    return status;
  }

  Duration retryAfter() {
    return retryAfter;
  }
}
