package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.expenseBody;
import static com.felipe.ballast.entries.EntriesApi.with;
import static com.felipe.ballast.entries.TransfersApi.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.felipe.ballast.IntegrationTest;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Threads de verdade contra o Postgres do Testcontainers. Pool de 10 conexões: o teste fica em
// poucas threads para ninguém estourar o connection-timeout de 2 s esperando uma.
@IntegrationTest
class TransferConcurrencyTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired DataSource dataSource;
  private EntriesApi api;
  private TransfersApi transfers;
  private EntriesApi.Actor actor;
  private String accountA;
  private String accountB;
  private ExecutorService pool;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    transfers = new TransfersApi(mvc);
    actor = api.newActor();
    accountA = actor.accountId();
    accountB = api.newAccount(actor.session(), "B " + UUID.randomUUID());
    pool = Executors.newFixedThreadPool(6);
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
    CyclicBarrier start = new CyclicBarrier(tasks.size());
    List<CompletableFuture<T>> futures = new ArrayList<>();
    for (Callable<T> task : tasks) {
      futures.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  start.await(10, TimeUnit.SECONDS);
                  return task.call();
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              },
              pool));
    }
    List<T> results = new ArrayList<>();
    for (CompletableFuture<T> future : futures) {
      results.add(future.get(60, TimeUnit.SECONDS));
    }
    return results;
  }

  private long countLegs() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :id")
        .param("id", actor.user().id())
        .query(Long.class)
        .single();
  }

  private long countLinks() {
    return jdbc.sql("SELECT count(*) FROM transferencia WHERE usuario_id = :id")
        .param("id", actor.user().id())
        .query(Long.class)
        .single();
  }

  @Test
  void crossedTransfersFinishWithoutDeadlockOrServerError() throws Exception {
    for (int round = 0; round < 30; round++) {
      List<Integer> statuses =
          runTogether(
              List.of(
                  () ->
                      transfers.create(actor.session(), body(accountA, accountB, 100)).getStatus(),
                  () ->
                      transfers
                          .create(actor.session(), body(accountB, accountA, 100))
                          .getStatus()));

      assertThat(statuses).as("rodada " + round).containsExactly(201, 201);
    }

    assertThat(api.accountBalance(actor.session(), accountA)).isZero();
    assertThat(api.accountBalance(actor.session(), accountB)).isZero();
    assertThat(countLinks()).isEqualTo(60);
    assertThat(countLegs()).isEqualTo(120);
  }

  @Test
  void aTransferQueuedBehindAnArchiveOfTheDestinationIsRejected() throws Exception {
    // Uma conexão do teste segura a linha da conta destino; o arquivar entra na fila primeiro e a
    // transferência depois. Quando a trava solta, ela tem de enxergar a conta já arquivada.
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement("SELECT id FROM conta WHERE id = ? FOR UPDATE")) {
        statement.setObject(1, UUID.fromString(accountB));
        statement.executeQuery().close();
      }
      CompletableFuture<MockHttpServletResponse> archiving =
          CompletableFuture.supplyAsync(() -> call(() -> archive(accountB)), pool);
      awaitBlockedSessions(1);
      CompletableFuture<MockHttpServletResponse> transferring =
          CompletableFuture.supplyAsync(
              () -> call(() -> transfers.create(actor.session(), body(accountA, accountB, 100))),
              pool);
      awaitBlockedSessions(2);

      holder.commit();

      assertThat(archiving.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      MockHttpServletResponse response = transferring.get(10, TimeUnit.SECONDS);
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"conta arquivada\"}");
      assertThat(countLegs()).isZero();
      assertThat(countLinks()).isZero();
    }
  }

  @Test
  void deleteRacingWithAPutOnALegNeverLeavesAHalfPair() throws Exception {
    for (int round = 0; round < 15; round++) {
      JsonNode created = transfers.createOk(actor.session(), body(accountA, accountB, 100));
      String id = created.get("id").asString();
      String outId = created.get("saidaId").asString();
      long before = countLegs();

      List<Integer> statuses =
          runTogether(
              List.of(
                  () -> transfers.remove(actor.session(), id).getStatus(),
                  () ->
                      api.update(
                              actor.session(), outId, with(expenseBody(actor), "valorCentavos", 1))
                          .getStatus()));

      // O DELETE ganha de qualquer jeito (204). O PUT vê a perna viva (400) ou já apagada (404).
      assertThat(statuses.get(0)).as("rodada " + round).isEqualTo(204);
      assertThat(statuses.get(1)).as("rodada " + round).isIn(400, 404);
      assertThat(countLegs()).isEqualTo(before - 2);
      assertThat(countLinks()).isZero();
    }
    assertThat(countLegs()).isZero();
  }

  private MockHttpServletResponse archive(String accountId) throws Exception {
    return mvc.perform(
            patch("/contas/" + accountId)
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"arquivada\":true}"))
        .andReturn()
        .getResponse();
  }

  private void awaitBlockedSessions(int expected) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      Long blocked =
          jdbc.sql(
                  "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                      + " AND wait_event_type = 'Lock'")
              .query(Long.class)
              .single();
      if (blocked >= expected) {
        return;
      }
      Thread.sleep(20);
    }
    throw new IllegalStateException("as sessões não ficaram esperando a trava");
  }

  private static <T> T call(Callable<T> action) {
    try {
      return action.call();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
