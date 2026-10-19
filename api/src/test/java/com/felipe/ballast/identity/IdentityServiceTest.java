package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class IdentityServiceTest {

  @Autowired UserAccountRepository users;
  @Autowired LoginSessionRepository sessions;
  @Autowired Clock clock;

  private CountingHasher hasher;
  private IdentityService service;

  // Conta as chamadas sem trocar o algoritmo.
  private static class CountingHasher extends PasswordHasher {
    final AtomicInteger hashCalls = new AtomicInteger();
    final AtomicInteger dummyCalls = new AtomicInteger();

    CountingHasher() {
      super(new IdentityProperties("false", "", "", new IdentityProperties.Password(8, 1, 1)));
    }

    @Override
    public String hash(String rawPassword) {
      hashCalls.incrementAndGet();
      return super.hash(rawPassword);
    }

    @Override
    public boolean matchesDummy(String rawPassword) {
      dummyCalls.incrementAndGet();
      return super.matchesDummy(rawPassword);
    }
  }

  private static String uniqueEmail() {
    return "t" + UUID.randomUUID().toString().replace("-", "") + "@exemplo.test";
  }

  @BeforeEach
  void setUp() {
    hasher = new CountingHasher();
    service = new IdentityService(users, sessions, hasher, clock);
  }

  @Test
  void hashesBeforeInsertingSoDuplicateAndNewEmailsCostTheSame() {
    String email = uniqueEmail();

    assertThat(service.register(email, "senha-de-teste-12345", null)).isPresent();
    assertThat(service.register(email, "senha-de-teste-12345", null)).isEmpty();

    assertThat(hasher.hashCalls).hasValue(2);
  }

  @Test
  void unknownEmailRunsTheDummyVerificationSoTheTimingDoesNotGiveTheAccountAway() {
    assertThat(service.authenticate(uniqueEmail(), "qualquer-senha-123")).isEmpty();

    assertThat(hasher.dummyCalls).hasValue(1);
  }

  @Test
  void knownEmailDoesNotRunTheDummyVerification() {
    String email = uniqueEmail();
    service.register(email, "senha-de-teste-12345", null);

    assertThat(service.authenticate(email, "senha-errada-12345")).isEmpty();
    assertThat(service.authenticate(email, "senha-de-teste-12345")).isPresent();

    assertThat(hasher.dummyCalls).hasValue(0);
  }
}
