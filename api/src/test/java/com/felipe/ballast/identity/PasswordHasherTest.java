package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PasswordHasherTest {

  // Parâmetros baratos: a suíte hasheia dezenas de vezes e 64 MiB por hash a deixaria lenta.
  private static final IdentityProperties.Password CHEAP = new IdentityProperties.Password(8, 1, 1);

  private final PasswordHasher hasher =
      new PasswordHasher(new IdentityProperties("false", "", "", CHEAP));

  @Test
  void hashesAsArgon2idWithInjectedParameters() {
    assertThat(hasher.hash("uma-senha-qualquer")).startsWith("$argon2id$v=19$m=8,t=1,p=1$");
  }

  @Test
  void defaultParametersAreTheProductionOnes() {
    new ApplicationContextRunner()
        .withUserConfiguration(OnlyProperties.class)
        .run(
            context -> {
              PasswordHasher production =
                  new PasswordHasher(context.getBean(IdentityProperties.class));
              String hash = production.hash("uma-senha-qualquer");
              String[] parts = hash.split("\\$");
              assertThat(parts[3]).isEqualTo("m=65536,t=3,p=2");
              Base64.Decoder decoder = Base64.getDecoder();
              assertThat(decoder.decode(parts[4])).hasSize(16);
              assertThat(decoder.decode(parts[5])).hasSize(32);
            });
  }

  @Test
  void sameRawPasswordGetsDifferentSalts() {
    assertThat(hasher.hash("mesma-senha-12345")).isNotEqualTo(hasher.hash("mesma-senha-12345"));
  }

  @Test
  void matchesOnlyTheRightPassword() {
    String hash = hasher.hash("senha-correta-123");

    assertThat(hasher.matches("senha-correta-123", hash)).isTrue();
    assertThat(hasher.matches("senha-errada-1234", hash)).isFalse();
  }

  @Test
  void unreadableHashIsAMismatchNotAnException() {
    assertThat(hasher.matches("qualquer", "lixo")).isFalse();
    assertThat(hasher.matches("qualquer", "$argon2id$v=19$m=8,t=1,p=1$@@@$@@@")).isFalse();
    assertThat(hasher.matches("qualquer", "")).isFalse();
  }

  @Test
  void dummyVerificationAlwaysFails() {
    assertThat(hasher.matchesDummy("senha-correta-123")).isFalse();
  }

  @Configuration
  @EnableConfigurationProperties(IdentityProperties.class)
  static class OnlyProperties {}
}
