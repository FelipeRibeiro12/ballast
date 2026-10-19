package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  @Autowired SessionQueries sessionQueries;
  @Autowired LoginAttempts attempts;
  @Autowired Clock clock;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

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
    service = new IdentityService(users, sessions, sessionQueries, attempts, hasher, clock);
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
    assertThat(service.authenticate(uniqueEmail(), "qualquer-senha-123"))
        .isInstanceOf(IdentityService.LoginResult.BadCredentials.class);

    assertThat(hasher.dummyCalls).hasValue(1);
  }

  @Test
  void knownEmailDoesNotRunTheDummyVerification() {
    String email = uniqueEmail();
    service.register(email, "senha-de-teste-12345", null);

    assertThat(service.authenticate(email, "senha-errada-12345"))
        .isInstanceOf(IdentityService.LoginResult.BadCredentials.class);
    assertThat(service.authenticate(email, "senha-de-teste-12345"))
        .isInstanceOf(IdentityService.LoginResult.Success.class);

    assertThat(hasher.dummyCalls).hasValue(0);
  }

  // Fail-closed: a tentativa é contada antes do argon2, então uma falha depois dela não devolve
  // a chance ao atacante.
  @Test
  void keepsTheCountWhenTheVerificationFailsAfterTheReservation() {
    String email = uniqueEmail();
    PasswordHasher exploding =
        new PasswordHasher(
            new IdentityProperties("false", "", "", new IdentityProperties.Password(8, 1, 1))) {
          @Override
          public boolean matchesDummy(String rawPassword) {
            throw new IllegalStateException("falha simulada");
          }
        };
    IdentityService failing =
        new IdentityService(users, sessions, sessionQueries, attempts, exploding, clock);

    assertThatThrownBy(() -> failing.authenticate(email, "qualquer-senha-123"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(
            jdbc.sql("SELECT falhas FROM tentativa_login WHERE email = :email")
                .param("email", email)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }
}
