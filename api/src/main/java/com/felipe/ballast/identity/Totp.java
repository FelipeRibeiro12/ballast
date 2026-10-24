package com.felipe.ballast.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// TOTP da RFC 6238 com os parâmetros que todo autenticador aceita sem configurar: HMAC-SHA1,
// passo de 30 s, 6 dígitos (ADR-050). Só JDK.
final class Totp {

  static final int SECRET_BYTES = 20;
  private static final long STEP_SECONDS = 30;
  private static final int DIGITS = 6;
  private static final int MODULO = 1_000_000;
  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final SecureRandom RANDOM = new SecureRandom();

  private Totp() {}

  static byte[] newSecret() {
    byte[] secret = new byte[SECRET_BYTES];
    RANDOM.nextBytes(secret);
    return secret;
  }

  static long stepOf(Instant instant) {
    return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
  }

  static String codeAt(byte[] secret, long step) {
    byte[] hash;
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA1 ausente no JDK", e);
    }
    int offset = hash[hash.length - 1] & 0x0f;
    int binary =
        ((hash[offset] & 0x7f) << 24)
            | ((hash[offset + 1] & 0xff) << 16)
            | ((hash[offset + 2] & 0xff) << 8)
            | (hash[offset + 3] & 0xff);
    return String.format("%0" + DIGITS + "d", binary % MODULO);
  }

  // Aceita um passo antes e um depois, para relógio de celular adiantado ou atrasado em até 30 s.
  // Devolve o passo que bateu, para quem chama recusar o mesmo código usado de novo. Compara os
  // três sem parar no primeiro e em tempo constante.
  static OptionalLong match(byte[] secret, String code, Instant now) {
    if (code.length() != DIGITS || !code.chars().allMatch(c -> c >= '0' && c <= '9')) {
      return OptionalLong.empty();
    }
    byte[] given = code.getBytes(StandardCharsets.US_ASCII);
    long current = stepOf(now);
    OptionalLong matched = OptionalLong.empty();
    for (long step = current - 1; step <= current + 1; step++) {
      byte[] expected = codeAt(secret, step).getBytes(StandardCharsets.US_ASCII);
      if (MessageDigest.isEqual(expected, given) && matched.isEmpty()) {
        matched = OptionalLong.of(step);
      }
    }
    return matched;
  }

  // Base32 da RFC 4648 sem "=": é o formato do segredo no otpauth:// e no campo para digitar.
  static String base32(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte value : bytes) {
      buffer = (buffer << 8) | (value & 0xff);
      bits += 8;
      while (bits >= 5) {
        out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
    }
    return out.toString();
  }
}
