package com.felipe.ballast.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// O /healthz é público e toca o banco: num flood ele não pode tomar o pool nem encher o log.
@ExtendWith(OutputCaptureExtension.class)
class HealthFloodTest {

  private static final String FAILURE_LOG = "healthz: falha ao consultar o banco";

  private final TestClock clock = new TestClock();

  private static class FakeRepository extends HealthRepository {

    final AtomicInteger calls = new AtomicInteger();
    final CountDownLatch release = new CountDownLatch(1);
    volatile boolean block;
    volatile boolean fail;

    FakeRepository() {
      super(new DriverManagerDataSource());
    }

    @Override
    String findMessage() {
      calls.incrementAndGet();
      if (block) {
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      if (fail) {
        throw new DataAccessResourceFailureException("banco fora");
      }
      return "ok";
    }
  }

  @Test
  void secondCheckWhileTheFirstIsRunningAnswers503WithoutTouchingTheDatabase() throws Exception {
    FakeRepository repository = new FakeRepository();
    repository.block = true;
    HealthController controller = new HealthController(repository, clock);
    CompletableFuture<ResponseEntity<?>> first = CompletableFuture.supplyAsync(controller::check);
    while (repository.calls.get() == 0) {
      Thread.onSpinWait();
    }

    ResponseEntity<?> second = controller.check();
    repository.release.countDown();

    assertThat(second.getStatusCode().value()).isEqualTo(503);
    assertThat(second.getBody()).isEqualTo(new HealthError("indisponível"));
    assertThat(first.get(5, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
    assertThat(repository.calls).hasValue(1);
  }

  @Test
  void slotIsFreeAgainAfterAFailure() {
    FakeRepository repository = new FakeRepository();
    repository.fail = true;
    HealthController controller = new HealthController(repository, clock);
    controller.check();
    repository.fail = false;

    assertThat(controller.check().getStatusCode().value()).isEqualTo(200);
  }

  @Test
  void logsTheFailureAtMostOncePerMinute(CapturedOutput output) {
    FakeRepository repository = new FakeRepository();
    repository.fail = true;
    clock.freeze(Instant.parse("2026-10-23T12:00:00Z"));
    HealthController controller = new HealthController(repository, clock);

    for (int i = 0; i < 5; i++) {
      assertThat(controller.check().getStatusCode().value()).isEqualTo(503);
    }
    assertThat(output.getOut().split(FAILURE_LOG, -1)).hasSize(2);

    clock.advance(Duration.ofMinutes(1));
    controller.check();
    assertThat(output.getOut().split(FAILURE_LOG, -1)).hasSize(3);
  }
}
