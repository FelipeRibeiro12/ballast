package com.felipe.ballast.identity;

// O teto do RegistrationCap foi atingido. Sai da transação do cadastro sem gravar nada.
class RegistrationFullException extends RuntimeException {

  RegistrationFullException() {
    super("limite de usuários atingido");
  }
}
