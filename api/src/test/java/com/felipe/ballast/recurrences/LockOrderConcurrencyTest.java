package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.rule;
import static com.felipe.ballast.recurrences.RecurrencesApi.withRule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

// Ordem única de travas (ADR-057): conta, template, lançamentos por id. Os testes repetem a corrida
// em várias rodadas porque deadlock depende de intercalação; uma rodada verde não prova nada.
@IntegrationTest
class LockOrderConcurrencyTest {

  private static final int ROUNDS = 25;
  private static final LocalDate FIRST = LocalDate.of(2026, 11, 5);

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired DataSource dataSource;
  @Autowired TestClock clock;
  @Autowired RecurrenceJob job;
  private RecurrencesApi api;
  private RecurrenceTestSupport db;
  private ExecutorService pool;

  @BeforeEach
  void setUp() {
    api = new RecurrencesApi(mvc);
    db = new RecurrenceTestSupport(jdbc);
    pool = Executors.newFixedThreadPool(4);
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    clock.reset();
  }

  private List<MockHttpServletResponse> runTogether(List<Callable<MockHttpServletResponse>> tasks)
      throws Exception {
    CyclicBarrier start = new CyclicBarrier(tasks.size());
    List<CompletableFuture<MockHttpServletResponse>> futures = new ArrayList<>();
    for (Callable<MockHttpServletResponse> task : tasks) {
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
    List<MockHttpServletResponse> results = new ArrayList<>();
    for (CompletableFuture<MockHttpServletResponse> future : futures) {
      results.add(future.get(60, TimeUnit.SECONDS));
    }
    return results;
  }

  private Map<String, Object> occurrenceBody(RecurrencesApi.Actor who, String title) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tipo", "despesa");
    body.put("titulo", title);
    body.put("valorCentavos", 150_000);
    body.put("categoriaId", who.expenseCategoryId());
    body.put("contaId", who.accountId());
    body.put("data", "2026-11-20");
    return body;
  }

  @Test
  void editingARecurrenceWhileEditingItsOccurrenceNeverDeadlocks() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      RecurrencesApi.Actor who = api.newActor();
      String template =
          api.createOk(who.session(), monthlyBody(who)).get("recorrencia").get("id").asString();
      String occurrence = db.byKey(template, FIRST).orElseThrow().id();

      List<MockHttpServletResponse> responses =
          runTogether(
              List.of(
                  () ->
                      api.update(who.session(), template, with(monthlyBody(who), "titulo", "Novo")),
                  () -> api.putEntry(who.session(), occurrence, occurrenceBody(who, "Editada"))));

      assertThat(responses.get(0).getStatus())
          .as("PUT recorrência, rodada " + round)
          .isEqualTo(200);
      assertThat(responses.get(1).getStatus()).as("PUT ocorrência, rodada " + round).isIn(200, 404);
    }
  }

  @Test
  void deletingTheAccountWhileEditingAnOccurrenceNeverDeadlocks() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      RecurrencesApi.Actor who = api.newActor();
      String template =
          api.createOk(who.session(), monthlyBody(who)).get("recorrencia").get("id").asString();
      String occurrence = db.byKey(template, FIRST).orElseThrow().id();

      List<MockHttpServletResponse> responses =
          runTogether(
              List.of(
                  () ->
                      mvc.perform(delete("/contas/" + who.accountId()).cookie(who.session()))
                          .andReturn()
                          .getResponse(),
                  () -> api.putEntry(who.session(), occurrence, occurrenceBody(who, "Editada"))));

      assertThat(responses.get(0).getStatus()).as("DELETE conta, rodada " + round).isEqualTo(204);
      assertThat(responses.get(1).getStatus()).as("PUT ocorrência, rodada " + round).isIn(200, 404);
    }
  }

  @Test
  void theJobWaitsForTheAccountSoNothingIsPlannedInAnArchivingAccount() throws Exception {
    RecurrencesApi.Actor who = api.newActor();
    // Diária: cada dia que passa acrescenta uma data ao horizonte, então o job tem o que inserir.
    api.createOk(
        who.session(), withRule(monthlyBody(who), rule("diaria", Map.of("inicio", "2026-10-19"))));
    clock.advance(Duration.ofDays(1));
    long before = countOnAccount(who.accountId());

    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      // O mesmo UPDATE do arquivar, ainda sem commit.
      try (var statement =
          holder.prepareStatement("UPDATE conta SET arquivada = true WHERE id = ?")) {
        statement.setObject(1, UUID.fromString(who.accountId()));
        statement.executeUpdate();
      }
      job.run();
      holder.commit();
    }

    assertThat(countOnAccount(who.accountId())).isEqualTo(before);
  }

  private long countOnAccount(String accountId) {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE conta_id = :id")
        .param("id", UUID.fromString(accountId))
        .query(Long.class)
        .single();
  }

  private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
    return RecurrencesApi.with(body, key, value);
  }
}
