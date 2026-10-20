package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.rule;
import static com.felipe.ballast.recurrences.RecurrencesApi.withRule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// ADR-026: a espera por trava numa escrita de dinheiro tem prazo de 2 s. Uma conexão do teste
// segura a linha por mais que isso e a rota responde 503 sem gravar nada.
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LockTimeoutTest {

  private static final String UNAVAILABLE = "{\"erro\":\"serviço indisponível\"}";
  private static final LocalDate FIRST = LocalDate.of(2026, 11, 5);

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
    templateId =
        api.createOk(actor.session(), monthlyBody(actor)).get("recorrencia").get("id").asString();
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    clock.reset();
  }

  private String entryJson() {
    return "{\"tipo\":\"despesa\",\"titulo\":\"x\",\"valorCentavos\":100,\"categoriaId\":\""
        + actor.expenseCategoryId()
        + "\",\"contaId\":\""
        + actor.accountId()
        + "\",\"data\":\"2026-10-19\"}";
  }

  private void hold(Connection holder, String table, String lockMode, String id) throws Exception {
    holder.setAutoCommit(false);
    try (var statement =
        holder.prepareStatement("SELECT id FROM " + table + " WHERE id = ? " + lockMode)) {
      statement.setObject(1, UUID.fromString(id));
      statement.executeQuery().close();
    }
  }

  // Roda a chamada com a trava ocupada e confere o 503 perto de 2 s. O teto de 15 s existe para o
  // teste falhar em vez de esperar o socketTimeout se o prazo não existir.
  private void assertUnavailableAfterAboutTwoSeconds(Callable<MockHttpServletResponse> call)
      throws Exception {
    assertUnavailableBetween(call, 1_800L, 6_000L);
  }

  private void assertUnavailableBetween(
      Callable<MockHttpServletResponse> call, long minMs, long maxMs) throws Exception {
    long started = System.nanoTime();
    CompletableFuture<MockHttpServletResponse> future =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return call.call();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            },
            pool);

    MockHttpServletResponse response = future.get(15, TimeUnit.SECONDS);

    long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(RecurrencesApi.text(response)).isEqualTo(UNAVAILABLE);
    assertThat(elapsedMs).isBetween(minMs, maxMs);
  }

  private long entriesOfAccount() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE conta_id = :id AND template_id IS NULL")
        .param("id", UUID.fromString(actor.accountId()))
        .query(Long.class)
        .single();
  }

  @Test
  void postLancamentoGives503AfterAboutTwoSecondsAndWritesNothing() throws Exception {
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "conta", "FOR UPDATE", actor.accountId());

      assertUnavailableAfterAboutTwoSeconds(
          () ->
              mvc.perform(
                      post("/lancamentos")
                          .cookie(actor.session())
                          .contentType("application/json")
                          .content(entryJson()))
                  .andReturn()
                  .getResponse());
    }
    assertThat(entriesOfAccount()).isZero();
  }

  @Test
  void confirmGives503AndTheOccurrenceStaysPlanned() throws Exception {
    String id = db.byKey(templateId, FIRST).orElseThrow().id();
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "conta", "FOR UPDATE", actor.accountId());

      assertUnavailableAfterAboutTwoSeconds(() -> api.confirm(actor.session(), id));
    }
    RecurrenceTestSupport.Row row = db.byKey(templateId, FIRST).orElseThrow();
    assertThat(row.status()).isEqualTo("previsto");
    assertThat(row.detached()).isFalse();
  }

  @Test
  void cancelGives503WhenTheOccurrenceRowIsBusy() throws Exception {
    String id = db.byKey(templateId, FIRST).orElseThrow().id();
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "lancamento", "FOR UPDATE", id);

      assertUnavailableAfterAboutTwoSeconds(() -> api.cancel(actor.session(), id));
    }
    assertThat(db.byKey(templateId, FIRST).orElseThrow().status()).isEqualTo("previsto");
  }

  @Test
  void putAndDeleteOfAnEntryGive503WhenTheAccountIsBusy() throws Exception {
    MockHttpServletResponse created =
        mvc.perform(
                post("/lancamentos")
                    .cookie(actor.session())
                    .contentType("application/json")
                    .content(entryJson()))
            .andReturn()
            .getResponse();
    String id = RecurrencesApi.body(created).get("id").asString();
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "conta", "FOR UPDATE", actor.accountId());

      assertUnavailableAfterAboutTwoSeconds(
          () ->
              mvc.perform(
                      org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                              "/lancamentos/" + id)
                          .cookie(actor.session())
                          .contentType("application/json")
                          .content(entryJson().replace("\"x\"", "\"y\"")))
                  .andReturn()
                  .getResponse());
      assertUnavailableAfterAboutTwoSeconds(() -> api.deleteEntry(actor.session(), id));
    }
    assertThat(entriesOfAccount()).isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT titulo FROM lancamento WHERE id = :id")
                .param("id", UUID.fromString(id))
                .query(String.class)
                .single())
        .isEqualTo("x");
  }

  @Test
  void transferCreateAndDeleteGive503WhenAnAccountIsBusy() throws Exception {
    String other = api.newAccount(actor.session(), "Destino " + UUID.randomUUID());
    String body =
        "{\"contaOrigemId\":\""
            + actor.accountId()
            + "\",\"contaDestinoId\":\""
            + other
            + "\",\"valorCentavos\":500,\"data\":\"2026-10-19\"}";
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "conta", "FOR UPDATE", other);

      assertUnavailableAfterAboutTwoSeconds(
          () ->
              mvc.perform(
                      post("/transferencias")
                          .cookie(actor.session())
                          .contentType("application/json")
                          .content(body))
                  .andReturn()
                  .getResponse());
    }
    assertThat(
            jdbc.sql("SELECT count(*) FROM transferencia WHERE usuario_id = :id")
                .param("id", actor.user().id())
                .query(Long.class)
                .single())
        .isZero();
    MockHttpServletResponse created =
        mvc.perform(
                post("/transferencias")
                    .cookie(actor.session())
                    .contentType("application/json")
                    .content(body))
            .andReturn()
            .getResponse();
    assertThat(created.getStatus()).isEqualTo(201);
    String transferId = RecurrencesApi.body(created).get("id").asString();
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "conta", "FOR UPDATE", other);

      assertUnavailableAfterAboutTwoSeconds(
          () ->
              mvc.perform(delete("/transferencias/" + transferId).cookie(actor.session()))
                  .andReturn()
                  .getResponse());
    }
    assertThat(
            jdbc.sql("SELECT count(*) FROM transferencia WHERE usuario_id = :id")
                .param("id", actor.user().id())
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void postRecorrenciaGives503WhenTheAccountIsBusyAndWritesNothing() throws Exception {
    long templatesBefore = db.countTemplates(actor.user().id());
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "conta", "FOR UPDATE", actor.accountId());

      assertUnavailableAfterAboutTwoSeconds(() -> api.create(actor.session(), monthlyBody(actor)));
    }
    assertThat(db.countTemplates(actor.user().id())).isEqualTo(templatesBefore);
    assertThat(db.countRules(actor.user().id())).isEqualTo(templatesBefore);
  }

  private boolean accountArchived() {
    return jdbc.sql("SELECT arquivada FROM conta WHERE id = :id")
        .param("id", UUID.fromString(actor.accountId()))
        .query(Boolean.class)
        .single();
  }

  private MockHttpServletResponse patchAccount(boolean archived) throws Exception {
    return mvc.perform(
            patch("/contas/" + actor.accountId())
                .cookie(actor.session())
                .contentType("application/json")
                .content("{\"arquivada\":" + archived + "}"))
        .andReturn()
        .getResponse();
  }

  @Test
  void archivingAndUnarchivingGive503WhenTheAccountIsBusyAndChangeNothing() throws Exception {
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "conta", "FOR UPDATE", actor.accountId());

      assertUnavailableAfterAboutTwoSeconds(() -> patchAccount(true));
    }
    assertThat(accountArchived()).isFalse();
    assertThat(patchAccount(true).getStatus()).isEqualTo(200);
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "conta", "FOR UPDATE", actor.accountId());

      assertUnavailableAfterAboutTwoSeconds(() -> patchAccount(false));
    }
    assertThat(accountArchived()).isTrue();
  }

  // O job insere em conta com a trava ocupada: o FK do INSERT também espera a conta. A espera do
  // job não pode empurrar a da escrita do usuário para além do prazo (B1 do QA da F5).
  @Test
  void aWriteGives503InAboutTwoSecondsEvenWithTheJobWaitingOnTheSameAccount() throws Exception {
    Map<String, Object> daily =
        withRule(monthlyBody(actor), rule("diaria", Map.of("inicio", "2026-10-19")));
    api.createOk(actor.session(), daily);
    clock.advance(Duration.ofDays(1));
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "conta", "FOR UPDATE", actor.accountId());
      CompletableFuture<Void> running = CompletableFuture.runAsync(job::run, pool);
      awaitBlockedSessions(1);

      assertUnavailableBetween(
          () ->
              mvc.perform(
                      post("/lancamentos")
                          .cookie(actor.session())
                          .contentType("application/json")
                          .content(entryJson()))
                  .andReturn()
                  .getResponse(),
          1_800L,
          2_300L);
      running.get(30, TimeUnit.SECONDS);
    }
    assertThat(entriesOfAccount()).isZero();
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

  @Test
  void deactivateGives503WhenTheTemplateIsBusyAndStaysActive() throws Exception {
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "template_lancamento", "FOR NO KEY UPDATE", templateId);

      assertUnavailableAfterAboutTwoSeconds(() -> api.deactivate(actor.session(), templateId));
    }
    assertThat(RecurrencesApi.body(api.find(actor.session(), templateId)).get("ativo").asBoolean())
        .isTrue();
    assertThat(db.countOccurrences(templateId)).isEqualTo(12);
  }

  @Test
  void theJobSkipsTheBusyTemplateLogsItAndGoesOnToTheNext() throws Exception {
    Map<String, Object> daily =
        withRule(monthlyBody(actor), rule("diaria", Map.of("inicio", "2026-10-19")));
    String busy = api.createOk(actor.session(), daily).get("recorrencia").get("id").asString();
    RecurrencesApi.Actor other = api.newActor();
    String free =
        api.createOk(
                other.session(),
                withRule(monthlyBody(other), rule("diaria", Map.of("inicio", "2026-10-19"))))
            .get("recorrencia")
            .get("id")
            .asString();
    clock.advance(Duration.ofDays(1));
    long busyBefore = db.countOccurrences(busy);
    long freeBefore = db.countOccurrences(free);
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "template_lancamento", "FOR NO KEY UPDATE", busy);

      long started = System.nanoTime();
      CompletableFuture.runAsync(job::run, pool).get(30, TimeUnit.SECONDS);
      assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(15_000);
    }

    assertThat(db.countOccurrences(busy)).isEqualTo(busyBefore);
    assertThat(db.countOccurrences(free)).isEqualTo(freeBefore + 1);
  }

  @Test
  void theJobLogsTheSkippedTemplateWithoutAStackTrace(CapturedOutput output) throws Exception {
    Map<String, Object> daily =
        withRule(monthlyBody(actor), rule("diaria", Map.of("inicio", "2026-10-19")));
    String busy = api.createOk(actor.session(), daily).get("recorrencia").get("id").asString();
    clock.advance(Duration.ofDays(1));
    try (Connection holder = dataSource.getConnection()) {
      hold(holder, "template_lancamento", "FOR NO KEY UPDATE", busy);

      CompletableFuture.runAsync(job::run, pool).get(30, TimeUnit.SECONDS);
    }

    assertThat(output.getOut()).contains("recorrência pulada por trava ocupada").contains(busy);
    assertThat(output.getOut()).doesNotContain("falha ao materializar");
  }
}
