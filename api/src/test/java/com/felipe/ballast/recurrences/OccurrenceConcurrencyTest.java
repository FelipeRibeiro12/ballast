package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.rule;
import static com.felipe.ballast.recurrences.RecurrencesApi.withRule;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Confirmar e cancelar contra as outras escritas. Os casos com ordem conhecida usam uma conexão
// do teste segurando a linha: o Postgres atende a fila na ordem de chegada, então o resultado
// não depende de sorte. Pool de 10 conexões: no máximo 8 threads.
@IntegrationTest
class OccurrenceConcurrencyTest {

  private static final LocalDate FIRST = LocalDate.of(2026, 11, 5);

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired DataSource dataSource;
  @Autowired TestClock clock;
  private RecurrencesApi api;
  private RecurrenceTestSupport db;
  private RecurrencesApi.Actor actor;
  private ExecutorService pool;
  private String templateId;

  @BeforeEach
  void setUp() throws Exception {
    api = new RecurrencesApi(mvc);
    db = new RecurrenceTestSupport(jdbc);
    actor = api.newActor();
    pool = Executors.newFixedThreadPool(10);
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
    templateId =
        api.createOk(actor.session(), monthlyBody(actor)).get("recorrencia").get("id").asString();
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    clock.reset();
  }

  private String occurrenceId(LocalDate key) {
    return db.byKey(templateId, key).orElseThrow().id();
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

  private MockHttpServletResponse archive(boolean archived) throws Exception {
    return mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/contas/" + actor.accountId())
                .cookie(actor.session())
                .contentType("application/json")
                .content("{\"arquivada\":" + archived + "}"))
        .andReturn()
        .getResponse();
  }

  private void hold(Connection holder, String sql, String id) throws Exception {
    holder.setAutoCommit(false);
    try (var statement = holder.prepareStatement(sql)) {
      statement.setObject(1, UUID.fromString(id));
      statement.executeQuery().close();
    }
  }

  @Test
  void confirmingTheSameOccurrenceTwiceInParallelCountsOnce() throws Exception {
    String id = occurrenceId(FIRST);

    List<Integer> statuses =
        runTogether(
            List.of(
                () -> api.confirm(actor.session(), id).getStatus(),
                () -> api.confirm(actor.session(), id).getStatus()));

    assertThat(statuses).containsExactlyInAnyOrder(200, 400);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-150_000);
    assertThat(db.countByStatus(templateId, "realizado")).isEqualTo(1);
  }

  @Test
  void aConfirmQueuedBeforeTheDeactivationStaysRealizedAndIsNotDeleted() throws Exception {
    String id = occurrenceId(FIRST);
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "SELECT id FROM lancamento WHERE id = ? FOR UPDATE", id);
      CompletableFuture<MockHttpServletResponse> confirming =
          CompletableFuture.supplyAsync(() -> call(() -> api.confirm(actor.session(), id)), pool);
      awaitBlockedSessions(1);
      CompletableFuture<MockHttpServletResponse> deactivating =
          CompletableFuture.supplyAsync(
              () -> call(() -> api.deactivate(actor.session(), templateId)), pool);
      awaitBlockedSessions(2);

      holder.commit();

      assertThat(confirming.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(deactivating.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    }
    assertThat(db.occurrenceDates(templateId)).containsExactly(FIRST);
    assertThat(db.byKey(templateId, FIRST).orElseThrow().status()).isEqualTo("realizado");
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-150_000);
  }

  @Test
  void confirmingWhileDeactivatingNeverFailsWithAServerError() throws Exception {
    for (int round = 0; round < 10; round++) {
      RecurrencesApi.Actor who = api.newActor();
      String template =
          api.createOk(who.session(), monthlyBody(who)).get("recorrencia").get("id").asString();
      String id = db.byKey(template, FIRST).orElseThrow().id();

      List<MockHttpServletResponse> responses =
          runTogether(
              List.of(
                  () -> api.confirm(who.session(), id),
                  () -> api.deactivate(who.session(), template)));

      int confirm = responses.get(0).getStatus();
      assertThat(responses.get(1).getStatus()).as("desativar, rodada " + round).isEqualTo(200);
      var row = db.byKey(template, FIRST);
      if (confirm == 200) {
        // Confirmada antes: o desativar não apaga, porque só apaga previstas.
        assertThat(row).as("rodada " + round).isPresent();
        assertThat(row.get().status()).isEqualTo("realizado");
        assertThat(db.countOccurrences(template)).isEqualTo(1);
      } else {
        // Desativada antes: a ocorrência já tinha sido apagada.
        assertThat(confirm).as("rodada " + round).isEqualTo(404);
        assertThat(db.countOccurrences(template)).isZero();
      }
    }
  }

  @Test
  void aConfirmQueuedBehindTheArchiveIsRejectedAsArchived() throws Exception {
    String id = occurrenceId(FIRST);
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "SELECT id FROM conta WHERE id = ? FOR UPDATE", actor.accountId());
      CompletableFuture<MockHttpServletResponse> archiving =
          CompletableFuture.supplyAsync(() -> call(() -> archive(true)), pool);
      awaitBlockedSessions(1);
      CompletableFuture<MockHttpServletResponse> confirming =
          CompletableFuture.supplyAsync(() -> call(() -> api.confirm(actor.session(), id)), pool);
      awaitBlockedSessions(2);

      holder.commit();

      assertThat(archiving.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      MockHttpServletResponse confirm = confirming.get(20, TimeUnit.SECONDS);
      assertThat(confirm.getStatus()).isEqualTo(400);
      assertThat(RecurrencesApi.text(confirm)).isEqualTo("{\"erro\":\"conta arquivada\"}");
    }
    assertThat(db.byKey(templateId, FIRST).orElseThrow().status()).isEqualTo("previsto");
  }

  @Test
  void aConfirmQueuedBeforeTheArchiveWinsAndTheArchiveStillAnswers200() throws Exception {
    String id = occurrenceId(FIRST);
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "SELECT id FROM conta WHERE id = ? FOR UPDATE", actor.accountId());
      CompletableFuture<MockHttpServletResponse> confirming =
          CompletableFuture.supplyAsync(() -> call(() -> api.confirm(actor.session(), id)), pool);
      awaitBlockedSessions(1);
      CompletableFuture<MockHttpServletResponse> archiving =
          CompletableFuture.supplyAsync(() -> call(() -> archive(true)), pool);
      awaitBlockedSessions(2);

      holder.commit();

      assertThat(confirming.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(archiving.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    }
    assertThat(db.byKey(templateId, FIRST).orElseThrow().status()).isEqualTo("realizado");
  }

  @Test
  void noOccurrenceIsConfirmedAfterTheArchiveAnswered() throws Exception {
    String daily =
        api.createOk(
                actor.session(),
                withRule(monthlyBody(actor), rule("diaria", Map.of("inicio", "2026-10-19"))))
            .get("recorrencia")
            .get("id")
            .asString();
    List<String> ids =
        jdbc.sql("SELECT id::text FROM lancamento WHERE template_id = :t ORDER BY ocorrencia_data")
            .param("t", UUID.fromString(daily))
            .query(String.class)
            .list();
    List<CompletableFuture<Integer>> confirms = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String id = ids.get(i);
      confirms.add(
          CompletableFuture.supplyAsync(
              () -> call(() -> api.confirm(actor.session(), id)).getStatus(), pool));
    }
    Thread.sleep(Duration.ofMillis(30));

    MockHttpServletResponse archived = archive(true);
    long realizedRightAfter = db.countByStatus(daily, "realizado");
    Thread.sleep(Duration.ofMillis(300));

    long ok = 0;
    for (CompletableFuture<Integer> confirm : confirms) {
      int status = confirm.get(30, TimeUnit.SECONDS);
      assertThat(status).isIn(200, 400);
      if (status == 200) {
        ok++;
      }
    }
    assertThat(archived.getStatus()).isEqualTo(200);
    assertThat(db.countByStatus(daily, "realizado")).isEqualTo(realizedRightAfter).isEqualTo(ok);
  }

  @Test
  void cancellingTheSameOccurrenceTwiceInParallelCancelsOnce() throws Exception {
    String id = occurrenceId(FIRST);
    List<Integer> statuses =
        runTogether(
            List.of(
                () -> api.cancel(actor.session(), id).getStatus(),
                () -> api.cancel(actor.session(), id).getStatus()));

    assertThat(statuses).containsExactlyInAnyOrder(200, 400);
    assertThat(db.byKey(templateId, FIRST).orElseThrow().status()).isEqualTo("cancelado");
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
