package com.felipe.ballast.identity;

// MFA_CHAVE ausente. Vira 503 no controller do MFA.
class MfaUnavailableException extends RuntimeException {

  MfaUnavailableException() {
    super("MFA_CHAVE não configurada");
  }
}
