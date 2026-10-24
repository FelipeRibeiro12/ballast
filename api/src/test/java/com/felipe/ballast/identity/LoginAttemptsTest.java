package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class LoginAttemptsTest {

  @Autowired LoginAttempts attempts;
  @Autowired JdbcClient jdbc;

  // Ler e depois gravar concederia todas; o UPSERT condicional concede só as que cabem antes do
  // atraso e conta cada uma.
  @Test
  void simultaneousReservationsGrantOnlyTheFreeOnesPlusTheFourth() throws Exception {
    String email = TestUsers.uniqueEmail();
    Instant now = Instant.parse("2026-03-10T12:00:00Z");
    int threads = 40;

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<OptionalInt>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      results.add(
          pool.submit(
              () -> {
                start.await();
                return attempts.reserve(email, "127.0.0.1", now);
              }));
    }
    start.countDown();
    long granted = 0;
    for (Future<OptionalInt> result : results) {
      if (result.get().isPresent()) {
        granted++;
      }
    }
    pool.shutdown();

    int stored =
        jdbc.sql("SELECT falhas FROM tentativa_login WHERE email = :email")
            .param("email", email)
            .query(Integer.class)
            .single();
    assertThat(granted).isEqualTo(LoginAttempts.FREE_FAILURES + 1);
    assertThat(stored).isEqualTo(LoginAttempts.FREE_FAILURES + 1);
  }

  @Test
  void delayAfterCapsTheExponentBeforeComputing() {
    assertThat(LoginAttempts.delayAfter(3)).isEqualTo(Duration.ZERO);
    assertThat(LoginAttempts.delayAfter(4)).isEqualTo(Duration.ofSeconds(2));
    assertThat(LoginAttempts.delayAfter(12)).isEqualTo(Duration.ofSeconds(512));
    assertThat(LoginAttempts.delayAfter(13)).isEqualTo(LoginAttempts.MAX_DELAY);
    assertThat(LoginAttempts.delayAfter(Integer.MAX_VALUE)).isEqualTo(LoginAttempts.MAX_DELAY);
  }
}
