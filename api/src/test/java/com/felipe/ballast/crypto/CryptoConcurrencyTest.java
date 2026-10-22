package com.felipe.ballast.crypto;

import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

// Threads de verdade contra o Postgres do Testcontainers, poucas para caber no pool de 10.
@IntegrationTest
class CryptoConcurrencyTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private CryptoApi api;
  private CryptoApi.Actor actor;
  private String bitcoin;
  private ExecutorService pool;

  @BeforeEach
  void setUp() throws Exception {
    api = new CryptoApi(mvc);
    actor = api.newActor();
    bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
    pool = Executors.newFixedThreadPool(4);
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  private List<Integer> runTogether(List<Callable<Integer>> tasks) throws Exception {
    CyclicBarrier start = new CyclicBarrier(tasks.size());
    List<Future<Integer>> futures = new ArrayList<>();
    for (Callable<Integer> task : tasks) {
      futures.add(
          pool.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return task.call();
              }));
    }
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> future : futures) {
      statuses.add(future.get(20, TimeUnit.SECONDS));
    }
    return statuses;
  }

  @Test
  void fourSimultaneousSalesOfTheWholePositionLetOnlyOneThrough() throws Exception {
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2026-03-01", 100));
    List<Callable<Integer>> sales = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      sales.add(
          () ->
              api.move(
                      actor.session(),
                      movement(actor.cryptoId(), bitcoin, "venda", "1", "2026-03-02", 100))
                  .getStatus());
    }

    List<Integer> statuses = runTogether(sales);

    assertThat(statuses).containsExactlyInAnyOrder(201, 400, 400, 400);
    long sold =
        jdbc.sql("SELECT count(*) FROM movimento_cripto WHERE usuario_id = :id AND tipo = 'venda'")
            .param("id", actor.user().id())
            .query(Long.class)
            .single();
    assertThat(sold).isEqualTo(1);
  }

  @Test
  void deletingTheBuyWhileSellingNeverLeavesANegativePosition() throws Exception {
    String buy =
        api.moveOk(
            actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2026-03-01", 100));

    List<Integer> statuses =
        runTogether(
            List.of(
                () -> api.removeMovement(actor.session(), buy).getStatus(),
                () ->
                    api.move(
                            actor.session(),
                            movement(actor.cryptoId(), bitcoin, "venda", "1", "2026-03-02", 100))
                        .getStatus()));

    assertThat(statuses).containsAnyOf(204, 201).doesNotContain(500, 503);
    Long negativeDays =
        jdbc.sql(
                """
                SELECT count(*) FROM (
                  SELECT SUM(CASE tipo WHEN 'compra' THEN quantidade ELSE -quantidade END)
                         OVER (ORDER BY data) AS posicao
                  FROM movimento_cripto WHERE usuario_id = :id) s
                WHERE posicao < 0
                """)
            .param("id", actor.user().id())
            .query(Long.class)
            .single();
    assertThat(negativeDays).isZero();
  }
}
