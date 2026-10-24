package com.felipe.ballast.identity;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Cifra o segredo do TOTP com AES-256-GCM e a chave de MFA_CHAVE (ADR-050). Quem lê só o banco
// não gera código. O id do dono entra como dado autenticado, para a linha não valer em outro
// usuário. Sem chave, configurar MFA dá 503 e o login com MFA só aceita código de recuperação.
@Component
class MfaCipher {

  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  static final int SEALED_BYTES = NONCE_BYTES + Totp.SECRET_BYTES + TAG_BITS / 8;
  private static final String KEY_MESSAGE =
      "MFA_CHAVE deve ser 32 bytes em base64 (openssl rand -base64 32)";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  MfaCipher(@Value("${ballast.mfa.key:}") String base64Key) {
    this.key = base64Key == null || base64Key.isBlank() ? null : parse(base64Key.strip());
  }

  private static SecretKeySpec parse(String base64Key) {
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(base64Key);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(KEY_MESSAGE);
    }
    if (bytes.length != KEY_BYTES) {
      throw new IllegalArgumentException(KEY_MESSAGE);
    }
    return new SecretKeySpec(bytes, "AES");
  }

  boolean isAvailable() {
    return key != null;
  }

  byte[] encrypt(byte[] secret, UUID owner) {
    requireKey();
    byte[] nonce = new byte[NONCE_BYTES];
    RANDOM.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(bytesOf(owner));
      byte[] sealed = cipher.doFinal(secret);
      return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("falha ao cifrar o segredo do MFA", e);
    }
  }

  // Chave trocada, linha adulterada ou de outro usuário: tudo falha na tag do GCM.
  byte[] decrypt(byte[] sealed, UUID owner) {
    requireKey();
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
      cipher.updateAAD(bytesOf(owner));
      return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("segredo do MFA não abre com a chave atual");
    }
  }

  private void requireKey() {
    if (key == null) {
      throw new MfaUnavailableException();
    }
  }

  private static byte[] bytesOf(UUID id) {
    return ByteBuffer.allocate(16)
        .putLong(id.getMostSignificantBits())
        .putLong(id.getLeastSignificantBits())
        .array();
  }
}
