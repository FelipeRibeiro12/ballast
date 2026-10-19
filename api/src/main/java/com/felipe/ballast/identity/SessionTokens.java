package com.felipe.ballast.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

// ADR-015: o token em claro só existe aqui e no cookie; o banco guarda o SHA-256 dele.
final class SessionTokens {

  private static final int TOKEN_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private SessionTokens() {}

  static String generate() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  // SHA-256 da string do cookie, sem decodificar: o que chega do cliente pode ser qualquer
  // texto, e todo texto tem hash (token inválido simplesmente não acha sessão).
  static byte[] hash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 ausente no JDK", e);
    }
  }
}
