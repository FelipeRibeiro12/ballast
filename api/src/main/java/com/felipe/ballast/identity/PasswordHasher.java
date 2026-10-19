package com.felipe.ballast.identity;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

  private static final int SALT_BYTES = 16;
  private static final int OUTPUT_BYTES = 32;

  private final Argon2PasswordEncoder encoder;
  private final String dummyHash;

  PasswordHasher(IdentityProperties properties) {
    IdentityProperties.Password parameters = properties.password();
    this.encoder =
        new Argon2PasswordEncoder(
            SALT_BYTES,
            OUTPUT_BYTES,
            parameters.parallelism(),
            parameters.memoryKib(),
            parameters.iterations());
    // Gerado na construção do bean, antes de o servidor aceitar conexões: a primeira
    // tentativa com e-mail inexistente não pode ser mais lenta que as outras.
    this.dummyHash = encoder.encode("senha-ficticia-sem-uso");
  }

  public String hash(String rawPassword) {
    return encoder.encode(rawPassword);
  }

  // Hash ilegível conta como senha errada, nunca como erro.
  public boolean matches(String rawPassword, String hash) {
    try {
      return encoder.matches(rawPassword, hash);
    } catch (RuntimeException e) {
      return false;
    }
  }

  // Gasta o mesmo tempo de uma verificação real, para o tempo de resposta do login com
  // e-mail inexistente não denunciar que a conta não existe.
  public boolean matchesDummy(String rawPassword) {
    matches(rawPassword, dummyHash);
    return false;
  }
}
