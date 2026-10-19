package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.incomeBody;
import static com.felipe.ballast.entries.EntriesApi.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.felipe.ballast.IntegrationTest;
import java.sql.Connection;
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicBoolean;
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

// Concorrência de verdade, com threads e o Postgres do Testcontainers. O pool tem 10 conexões:
// os testes ficam em 8 threads para ninguém estourar o connection-timeout de 2 s esperando uma.
@IntegrationTest
class EntryConcurrencyTest {

  private static final long CEILING = 9_007_199_254_740_991L;
  private static final int THREADS = 8;

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired DataSource dataSource;
  private EntriesApi api;
  private EntriesApi.Actor actor;
  private ExecutorService pool;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    actor = api.newActor();
    pool = Executors.newFixedThreadPool(THREADS + 2);
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

  private long countEntries(String accountId) {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE conta_id = :id")
        .param("id", UUID.fromString(accountId))
        .query(Long.class)
        .single();
  }

  private MockHttpServletResponse archive(String accountId, boolean archived) throws Exception {
    return mvc.perform(
            patch("/contas/" + accountId)
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"arquivada\":" + archived + "}"))
        .andReturn()
        .getResponse();
  }

  @Test
  void parallelPostsThatTogetherPassTheCeilingNeverLeaveTheRange() throws Exception {
    // 3 de 3.000.000.000.000.000 cabem (9e15 < 9,007e15); o quarto já estoura.
    long chunk = 3_000_000_000_000_000L;
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
      tasks.add(
          () ->
              api.create(actor.session(), with(incomeBody(actor), "valorCentavos", chunk))
                  .getStatus());
    }

    List<Integer> statuses = runTogether(tasks);

    long created = statuses.stream().filter(s -> s == 201).count();
    long rejected = statuses.stream().filter(s -> s == 400).count();
    assertThat(created).isEqualTo(3);
    assertThat(rejected).isEqualTo(THREADS - 3);
    assertThat(countEntries(actor.accountId())).isEqualTo(created);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(3 * chunk);
  }

  @Test
  void aPostQueuedBehindAnArchiveIsRejectedAsArchived() throws Exception {
    // Uma transação de teste segura a linha da conta; o arquivar entra na fila primeiro e o POST
    // depois. Quando a trava solta, o POST tem de enxergar a conta já arquivada.
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement("SELECT id FROM conta WHERE id = ? FOR UPDATE")) {
        statement.setObject(1, UUID.fromString(actor.accountId()));
        statement.executeQuery().close();
      }
      CompletableFuture<MockHttpServletResponse> archiving =
          CompletableFuture.supplyAsync(() -> call(() -> archive(actor.accountId(), true)), pool);
      awaitBlockedSessions(1);
      CompletableFuture<MockHttpServletResponse> posting =
          CompletableFuture.supplyAsync(
              () -> call(() -> api.create(actor.session(), incomeBody(actor))), pool);
      awaitBlockedSessions(2);

      holder.commit();

      assertThat(archiving.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      MockHttpServletResponse post = posting.get(10, TimeUnit.SECONDS);
      assertThat(post.getStatus()).isEqualTo(400);
      assertThat(EntriesApi.text(post)).isEqualTo("{\"erro\":\"conta arquivada\"}");
      assertThat(countEntries(actor.accountId())).isZero();
    }
  }

  @Test
  void noEntryIsWrittenAfterTheArchiveAnswered200() throws Exception {
    AtomicBoolean stop = new AtomicBoolean();
    List<CompletableFuture<Integer>> posters = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
      posters.add(
          CompletableFuture.supplyAsync(
              () -> {
                int created = 0;
                while (!stop.get()) {
                  MockHttpServletResponse response =
                      call(() -> api.create(actor.session(), incomeBody(actor)));
                  if (response.getStatus() == 201) {
                    created++;
                  } else {
                    assertThat(response.getStatus()).isEqualTo(400);
                  }
                }
                return created;
              },
              pool));
    }
    Thread.sleep(Duration.ofMillis(400));

    MockHttpServletResponse archived = archive(actor.accountId(), true);
    long countRightAfter = countEntries(actor.accountId());
    // Os POSTs que estavam no ar quando o arquivar respondeu ainda têm tempo de gravar, se a
    // trava não segurar. Com a trava, todos já tinham confirmado antes do arquivar.
    Thread.sleep(Duration.ofMillis(500));
    stop.set(true);
    long created = 0;
    for (CompletableFuture<Integer> poster : posters) {
      created += poster.get(30, TimeUnit.SECONDS);
    }

    assertThat(archived.getStatus()).isEqualTo(200);
    assertThat(countRightAfter).isPositive();
    assertThat(countEntries(actor.accountId())).isEqualTo(countRightAfter).isEqualTo(created);
    assertThat(
            jdbc.sql("SELECT arquivada FROM conta WHERE id = :id")
                .param("id", UUID.fromString(actor.accountId()))
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  void crossedPutsBetweenTwoAccountsFinishWithoutDeadlockOrServerError() throws Exception {
    String accountB = api.newAccount(actor.session(), "B " + UUID.randomUUID());
    Map<String, Object> inA = with(incomeBody(actor), "valorCentavos", 100);
    Map<String, Object> inB = with(inA, "contaId", accountB);
    String idA = api.createOk(actor.session(), inA).get("id").asString();
    String idB = api.createOk(actor.session(), inB).get("id").asString();

    for (int round = 0; round < 30; round++) {
      // Rodada par: A vai para B e B vai para A. Rodada ímpar: voltam.
      Map<String, Object> bodyForA = round % 2 == 0 ? inB : inA;
      Map<String, Object> bodyForB = round % 2 == 0 ? inA : inB;
      List<Integer> statuses =
          runTogether(
              List.of(
                  () -> api.update(actor.session(), idA, bodyForA).getStatus(),
                  () -> api.update(actor.session(), idB, bodyForB).getStatus()));

      assertThat(statuses).as("rodada " + round).containsExactly(200, 200);
    }

    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(100);
    assertThat(api.accountBalance(actor.session(), accountB)).isEqualTo(100);
    JsonNode a = EntriesApi.body(api.find(actor.session(), idA));
    assertThat(a.get("contaId").asString()).isEqualTo(actor.accountId());
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
