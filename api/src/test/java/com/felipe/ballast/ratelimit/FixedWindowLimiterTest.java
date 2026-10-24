package com.felipe.ballast.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FixedWindowLimiterTest {

  // Começo exato de uma janela de 1 min, para o Retry-After dar conta redonda.
  private static final Instant START = Instant.parse("2026-10-23T12:00:00Z");

  @Test
  void allowsUpToTheLimitInsideTheWindow() {
    FixedWindowLimiter limiter = new FixedWindowLimiter(3, Duration.ofMinutes(1), 10);

    for (int i = 0; i < 3; i++) {
      assertThat(limiter.tryAcquire("a", START.plusSeconds(i)).allowed()).isTrue();
    }
    FixedWindowLimiter.Decision refused = limiter.tryAcquire("a", START.plusSeconds(20));

    assertThat(refused.allowed()).isFalse();
    assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(40));
  }

  @Test
  void countsEachKeyApart() {
    FixedWindowLimiter limiter = new FixedWindowLimiter(1, Duration.ofMinutes(1), 10);

    assertThat(limiter.tryAcquire("a", START).allowed()).isTrue();
    assertThat(limiter.tryAcquire("a", START).allowed()).isFalse();
    assertThat(limiter.tryAcquire("b", START).allowed()).isTrue();
  }

  @Test
  void nextWindowStartsFromZero() {
    FixedWindowLimiter limiter = new FixedWindowLimiter(1, Duration.ofMinutes(1), 10);
    limiter.tryAcquire("a", START);
    assertThat(limiter.tryAcquire("a", START.plusSeconds(59)).allowed()).isFalse();

    assertThat(limiter.tryAcquire("a", START.plusSeconds(60)).allowed()).isTrue();
  }

  @Test
  void flagsOnlyTheFirstRefusalOfTheWindow() {
    FixedWindowLimiter limiter = new FixedWindowLimiter(1, Duration.ofMinutes(1), 10);
    limiter.tryAcquire("a", START);

    assertThat(limiter.tryAcquire("a", START).firstRefusal()).isTrue();
    assertThat(limiter.tryAcquire("a", START).firstRefusal()).isFalse();
    assertThat(limiter.tryAcquire("a", START).firstRefusal()).isFalse();
  }

  @Test
  void fullTableRefusesNewKeysAndKeepsKnownOnes() {
    FixedWindowLimiter limiter = new FixedWindowLimiter(5, Duration.ofMinutes(1), 2);
    limiter.tryAcquire("a", START);
    limiter.tryAcquire("b", START);

    assertThat(limiter.tryAcquire("c", START).allowed()).isFalse();
    assertThat(limiter.tryAcquire("a", START).allowed()).isTrue();
  }

  @Test
  void fullTableDropsExpiredWindowsBeforeRefusing() {
    FixedWindowLimiter limiter = new FixedWindowLimiter(5, Duration.ofMinutes(1), 2);
    limiter.tryAcquire("a", START);
    limiter.tryAcquire("b", START);

    assertThat(limiter.tryAcquire("c", START.plusSeconds(60)).allowed()).isTrue();
    assertThat(limiter.size()).isEqualTo(1);
  }

  @Test
  void windowOfOneHourAnswersWithTheRestOfTheHour() {
    FixedWindowLimiter limiter = new FixedWindowLimiter(1, Duration.ofHours(1), 10);
    limiter.tryAcquire("a", START);

    assertThat(limiter.tryAcquire("a", START.plusSeconds(600)).retryAfter())
        .isEqualTo(Duration.ofMinutes(50));
  }
}
