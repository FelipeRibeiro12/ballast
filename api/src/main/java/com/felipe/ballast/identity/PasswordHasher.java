package com.felipe.ballast.identity;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

  private static final int SALT_BYTES = 16;
  private static final int OUTPUT_BYTES = 32;

  // Dois hashes ao mesmo tempo: um por núcleo da VM (ADR-044) e no máximo 128 MiB de argon2. Quem
  // passa da espera leva 503; o limite de login da F20.5 deixa isso raro fora de um ataque.
  static final int MAX_CONCURRENT = 2;
  static final Duration MAX_WAIT = Duration.ofSeconds(5);

  private final Argon2PasswordEncoder encoder;
  private final String dummyHash;
  private final Semaphore slots;
  private final Duration maxWait;

  @Autowired
  PasswordHasher(IdentityProperties properties) {
    this(properties, new Semaphore(MAX_CONCURRENT, true), MAX_WAIT);
  }

  PasswordHasher(IdentityProperties properties, Semaphore slots, Duration maxWait) {
    this.slots = slots;
    this.maxWait = maxWait;
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
    return withSlot(() -> encoder.encode(rawPassword));
  }

  // Hash ilegível conta como senha errada, nunca como erro.
  public boolean matches(String rawPassword, String hash) {
    return withSlot(() -> safeMatches(rawPassword, hash));
  }

  // Gasta o mesmo tempo de uma verificação real, para o tempo de resposta do login com
  // e-mail inexistente não denunciar que a conta não existe.
  public boolean matchesDummy(String rawPassword) {
    withSlot(() -> safeMatches(rawPassword, dummyHash));
    return false;
  }

  private boolean safeMatches(String rawPassword, String hash) {
    try {
      return encoder.matches(rawPassword, hash);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private <T> T withSlot(Supplier<T> work) {
    boolean acquired;
    try {
      acquired = slots.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PasswordHasherBusyException();
    }
    if (!acquired) {
      throw new PasswordHasherBusyException();
    }
    try {
      return work.get();
    } finally {
      slots.release();
    }
  }
}
