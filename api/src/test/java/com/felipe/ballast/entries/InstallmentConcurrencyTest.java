package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

// Threads de verdade contra o Postgres do Testcontainers. O pool tem 10 conexões: ficamos em 4.
@IntegrationTest
class InstallmentConcurrencyTest {

  private static final int THREADS = 4;

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private InstallmentsApi api;
  private InstallmentsApi.Shopper shopper;
  private ExecutorService pool;

  @BeforeEach
  void setUp() throws Exception {
    api = new InstallmentsApi(mvc);
    shopper = api.newShopper();
    pool = Executors.newFixedThreadPool(THREADS);
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  @Test
  void parallelPurchasesNearTheBalanceCeilingLetOnlyTheOnesThatFitThrough() throws Exception {
    // Três de 3.000.000.000.000.000 cabem (9e15 < 9,007e15); a quarta estoura o saldo.
    long total = 3_000_000_000_000_000L;
    CyclicBarrier start = new CyclicBarrier(THREADS);
    List<CompletableFuture<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
      Callable<Integer> task =
          () -> {
            start.await(10, TimeUnit.SECONDS);
            return api.create(
                    shopper.session(),
                    with(InstallmentsApi.purchaseBody(shopper), "valorTotalCentavos", total))
                .getStatus();
          };
      futures.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return task.call();
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              },
              pool));
    }
    List<Integer> statuses = new ArrayList<>();
    for (CompletableFuture<Integer> future : futures) {
      statuses.add(future.get(60, TimeUnit.SECONDS));
    }

    assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(3);
    assertThat(statuses.stream().filter(s -> s == 400).count()).isEqualTo(THREADS - 3);
    assertThat(
            jdbc.sql("SELECT count(*) FROM lancamento WHERE conta_id = :id")
                .param("id", UUID.fromString(shopper.cardId()))
                .query(Long.class)
                .single())
        .isEqualTo(9);
    assertThat(api.entries().accountBalance(shopper.session(), shopper.cardId()))
        .isEqualTo(-3 * total);
  }
}
