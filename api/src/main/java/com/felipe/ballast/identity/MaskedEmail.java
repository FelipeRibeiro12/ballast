package com.felipe.ballast.identity;

final class MaskedEmail {

  private MaskedEmail() {}

  // O e-mail inteiro é dado pessoal; a primeira letra e o domínio bastam para investigar um
  // incidente.
  static String of(String email) {
    int at = email.indexOf('@');
    if (at <= 0) {
      return "***";
    }
    int first = email.codePointAt(0);
    return new String(Character.toChars(first)) + "***" + email.substring(at);
  }
}
