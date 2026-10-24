package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.rule;
import static com.felipe.ballast.recurrences.RecurrencesApi.withRule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

  // UPDATE comum, como o de um PUT: a linha fica com escritor em andamento e o INSERT ... ON
  // CONFLICT do job espera por ela. Só FOR UPDATE não bastaria, o DO NOTHING não espera locker.
  private void holdOccurrenceRow(Connection holder, String occurrenceId) throws Exception {
    holder.setAutoCommit(false);
    try (var statement =
        holder.prepareStatement("UPDATE lancamento SET descricao = 'trava' WHERE id = ?")) {
      statement.setObject(1, UUID.fromString(occurrenceId));
      statement.executeUpdate();
    }
  }

  private MockHttpServletResponse postEntry() throws Exception {
    return mvc.perform(
            post("/lancamentos")
                .cookie(actor.session())
                .contentType("application/json")
                .content(
                    "{\"tipo\":\"despesa\",\"titulo\":\"x\",\"valorCentavos\":100,\"categoriaId\":\""
                        + actor.expenseCategoryId()
                        + "\",\"contaId\":\""
                        + actor.accountId()
                        + "\",\"data\":\"2026-10-20\"}"))
        .andReturn()
        .getResponse();
  }

  // O job trava a conta (ADR-057) e depois espera a linha ocupada. A espera dele é de 200 ms, então
  // quem precisa da conta espera no máximo isso, e não os 2 s de uma escrita comum.
  @Test
  void aJobWaitingForAnOccurrenceRowFreesTheAccountAfterItsShortTimeout() throws Exception {
    String busyRow = db.byKey(templateId, LocalDate.of(2026, 10, 25)).orElseThrow().id();
    String otherRow = db.byKey(templateId, LocalDate.of(2026, 10, 26)).orElseThrow().id();
    try (Connection holder = dataSource.getConnection()) {
      holdOccurrenceRow(holder, busyRow);
      CompletableFuture<Void> running = CompletableFuture.runAsync(job::run, pool);
      awaitBlockedSessions(1);

      long started = System.nanoTime();
      MockHttpServletResponse created = postEntry();
      MockHttpServletResponse confirmed = api.confirm(actor.session(), otherRow);
      long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

      assertThat(created.getStatus()).isEqualTo(201);
      assertThat(confirmed.getStatus()).isEqualTo(200);
      assertThat(elapsedMs).isLessThan(1_000);
      holder.commit();
      running.get(20, TimeUnit.SECONDS);
    }
    // O job desistiu da rodada por prazo e desfez tudo; a próxima rodada completa o horizonte.
    assertThat(db.countOccurrences(templateId)).isEqualTo(366);
  }

  // Arquivar entra na fila da conta atrás do job. Quando o job desiste ou termina, o arquivar
  // responde 200 e nenhuma prevista nova aparece na conta arquivada.
  @Test
  void noPlannedAppearsOnTheAccountAfterTheArchiveWhileTheJobIsWaiting() throws Exception {
    String busyRow = db.byKey(templateId, LocalDate.of(2026, 10, 25)).orElseThrow().id();
    try (Connection holder = dataSource.getConnection()) {
      holdOccurrenceRow(holder, busyRow);
      CompletableFuture<Void> running = CompletableFuture.runAsync(job::run, pool);
      awaitBlockedSessions(1);

      MockHttpServletResponse archived =
          mvc.perform(
                  patch("/contas/" + actor.accountId())
                      .cookie(actor.session())
                      .contentType("application/json")
                      .content("{\"arquivada\":true}"))
              .andReturn()
              .getResponse();
      assertThat(archived.getStatus()).isEqualTo(200);
      holder.commit();
      running.get(20, TimeUnit.SECONDS);
    }

    assertThat(db.byKey(templateId, LocalDate.of(2027, 10, 20))).isEmpty();
    assertThat(db.countOccurrences(templateId)).isEqualTo(366);
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
