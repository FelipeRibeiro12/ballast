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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

// Prova determinística com a trava do template, como os testes de fila da F3: uma conexão do
// teste segura a linha; o job e o PATCH entram na fila em ordem conhecida, e o Postgres atende
// na ordem de chegada. Sem a trava do template em um dos dois, a fila não se forma.
@IntegrationTest
class RecurrenceConcurrencyTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired DataSource dataSource;
  @Autowired TestClock clock;
  @Autowired RecurrenceJob job;
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
    pool = Executors.newFixedThreadPool(4);
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
    // Diária: cada dia que passa acrescenta uma data nova ao horizonte, então o job sempre tem
    // o que inserir.
    templateId =
        api.createOk(
                actor.session(),
                withRule(monthlyBody(actor), rule("diaria", Map.of("inicio", "2026-10-19"))))
            .get("recorrencia")
            .get("id")
            .asString();
    clock.advance(Duration.ofDays(1));
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    clock.reset();
  }

  // FOR NO KEY UPDATE é o que um UPDATE comum seguraria. Com FOR UPDATE a FK do INSERT já
  // esperaria,
  // e o teste passaria mesmo sem a trava do template na aplicação.
  private void holdTemplateLock(Connection holder) throws Exception {
    holder.setAutoCommit(false);
    try (var statement =
        holder.prepareStatement(
            "SELECT id FROM template_lancamento WHERE id = ? FOR NO KEY UPDATE")) {
      statement.setObject(1, UUID.fromString(templateId));
      statement.executeQuery().close();
    }
  }

  private long plannedFromToday() {
    return jdbc.sql(
            "SELECT count(*) FROM lancamento WHERE template_id = :id AND status = 'previsto'"
                + " AND ocorrencia_data >= :today")
        .param("id", UUID.fromString(templateId))
        .param("today", LocalDate.of(2026, 10, 20))
        .query(Long.class)
        .single();
  }

  @Test
  void aJobQueuedBeforeTheDeactivationLeavesNothingPlannedAfterIt() throws Exception {
    try (Connection holder = dataSource.getConnection()) {
      holdTemplateLock(holder);
      CompletableFuture<Void> running = CompletableFuture.runAsync(job::run, pool);
      awaitBlockedSessions(1);
      CompletableFuture<MockHttpServletResponse> patching =
          CompletableFuture.supplyAsync(
              () -> call(() -> api.deactivate(actor.session(), templateId)), pool);
      awaitBlockedSessions(2);

      holder.commit();

      running.get(20, TimeUnit.SECONDS);
      assertThat(patching.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(plannedFromToday()).isZero();
      assertThat(db.occurrenceDates(templateId)).containsExactly(LocalDate.of(2026, 10, 19));
    }
  }

  @Test
  void aJobQueuedAfterTheDeactivationInsertsNothing() throws Exception {
    try (Connection holder = dataSource.getConnection()) {
      holdTemplateLock(holder);
      CompletableFuture<MockHttpServletResponse> patching =
          CompletableFuture.supplyAsync(
              () -> call(() -> api.deactivate(actor.session(), templateId)), pool);
      awaitBlockedSessions(1);
      CompletableFuture<Void> running = CompletableFuture.runAsync(job::run, pool);
      awaitBlockedSessions(2);

      holder.commit();

      assertThat(patching.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      running.get(20, TimeUnit.SECONDS);
      assertThat(plannedFromToday()).isZero();
      assertThat(db.occurrenceDates(templateId)).containsExactly(LocalDate.of(2026, 10, 19));
    }
  }

  @Test
  void aJobQueuedBehindAnArchiveDoesNotWriteIntoTheArchivedAccount() throws Exception {
    // A conta fica travada pelo teste; o arquivar entra na fila e o job depois dele.
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement("SELECT id FROM conta WHERE id = ? FOR UPDATE")) {
        statement.setObject(1, UUID.fromString(actor.accountId()));
        statement.executeQuery().close();
      }
      CompletableFuture<Void> archiving =
          CompletableFuture.runAsync(
              () ->
                  call(
                      () -> {
                        api.setAccountArchived(actor.session(), actor.accountId(), true);
                        return null;
                      }),
              pool);
      awaitBlockedSessions(1);
      CompletableFuture<Void> running = CompletableFuture.runAsync(job::run, pool);
      awaitBlockedSessions(2);

      holder.commit();

      archiving.get(20, TimeUnit.SECONDS);
      running.get(20, TimeUnit.SECONDS);
      assertThat(db.countOccurrences(templateId)).isEqualTo(366);
    }
  }

  private interface Call<T> {
    T run() throws Exception;
  }

  private static <T> T call(Call<T> call) {
    try {
      return call.run();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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
    throw new IllegalStateException("esperava " + expected + " sessões na fila de trava");
  }
}
