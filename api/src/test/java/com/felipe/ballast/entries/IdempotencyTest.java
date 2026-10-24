package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.expenseBody;
import static com.felipe.ballast.entries.EntriesApi.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class IdempotencyTest {

  private static final String REPLAYED = "Idempotent-Replayed";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private EntriesApi api;
  private EntriesApi.Actor actor;
  private ExecutorService pool;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    actor = api.newActor();
    pool = Executors.newFixedThreadPool(8);
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    clock.reset();
  }

  private MockHttpServletResponse send(String path, Cookie session, String key, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body instanceof String s ? s : EntriesApi.JSON.writeValueAsString(body));
    if (session != null) {
      request.cookie(session);
    }
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private MockHttpServletResponse postEntry(String key, Object body) throws Exception {
    return send("/lancamentos", actor.session(), key, body);
  }

  private long entriesOf(String accountId) {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE conta_id = :id")
        .param("id", UUID.fromString(accountId))
        .query(Long.class)
        .single();
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  @Test
  void theSameKeyAndBodyReturnsTheOriginalResponseWithoutWritingAgain() throws Exception {
    String key = key();
    Map<String, Object> body = expenseBody(actor);

    MockHttpServletResponse first = postEntry(key, body);
    MockHttpServletResponse second = postEntry(key, body);

    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(first.getHeader(REPLAYED)).isNull();
    assertThat(second.getStatus()).isEqualTo(201);
    assertThat(EntriesApi.text(second)).isEqualTo(EntriesApi.text(first));
    assertThat(second.getHeader(REPLAYED)).isEqualTo("true");
    assertThat(second.getContentType()).startsWith("application/json");
    assertThat(entriesOf(actor.accountId())).isEqualTo(1);
  }

  @Test
  void withoutTheHeaderEveryRequestWrites() throws Exception {
    postEntry(null, expenseBody(actor));
    postEntry(null, expenseBody(actor));

    assertThat(entriesOf(actor.accountId())).isEqualTo(2);
  }

  @Test
  void theSameKeyWithAnotherBodyIs422AndWritesNothingNew() throws Exception {
    String key = key();
    postEntry(key, expenseBody(actor));

    MockHttpServletResponse second = postEntry(key, with(expenseBody(actor), "valorCentavos", 999));

    assertThat(second.getStatus()).isEqualTo(422);
    assertThat(EntriesApi.text(second))
        .isEqualTo("{\"erro\":\"Idempotency-Key já usada com outra requisição\"}");
    assertThat(entriesOf(actor.accountId())).isEqualTo(1);
  }

  @Test
  void theSameKeyOnAnotherRouteIs422() throws Exception {
    String key = key();
    String other = api.newAccount(actor.session(), "Destino " + UUID.randomUUID());
    postEntry(key, expenseBody(actor));

    MockHttpServletResponse transfer =
        send(
            "/transferencias",
            actor.session(),
            key,
            TransfersApi.body(actor.accountId(), other, 100));

    assertThat(transfer.getStatus()).isEqualTo(422);
  }

  @Test
  void anotherUserUsingTheSameKeyGetsItsOwnExecutionAndNeverTheOthersResponse() throws Exception {
    String key = key();
    MockHttpServletResponse mine = postEntry(key, expenseBody(actor));
    EntriesApi.Actor stranger = api.newActor();

    MockHttpServletResponse theirs =
        send("/lancamentos", stranger.session(), key, expenseBody(stranger));

    assertThat(theirs.getStatus()).isEqualTo(201);
    assertThat(theirs.getHeader(REPLAYED)).isNull();
    assertThat(EntriesApi.body(theirs).get("id")).isNotEqualTo(EntriesApi.body(mine).get("id"));
    assertThat(EntriesApi.body(theirs).get("contaId").asString()).isEqualTo(stranger.accountId());
    assertThat(entriesOf(actor.accountId())).isEqualTo(1);
    assertThat(entriesOf(stranger.accountId())).isEqualTo(1);
  }

  @Test
  void anotherUserSendingADifferentBodyWithMyKeyIsNot422() throws Exception {
    String key = key();
    postEntry(key, expenseBody(actor));
    EntriesApi.Actor stranger = api.newActor();

    MockHttpServletResponse theirs =
        send(
            "/lancamentos",
            stranger.session(),
            key,
            with(expenseBody(stranger), "valorCentavos", 1));

    assertThat(theirs.getStatus()).isEqualTo(201);
  }

  @Test
  void concurrentIdenticalRequestsWriteOnceAndAllGetTheSameAnswer() throws Exception {
    String key = key();
    Map<String, Object> body = expenseBody(actor);
    List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      tasks.add(() -> postEntry(key, body));
    }

    List<MockHttpServletResponse> responses = runTogether(tasks);

    assertThat(entriesOf(actor.accountId())).isEqualTo(1);
    String original = EntriesApi.text(responses.get(0));
    for (MockHttpServletResponse response : responses) {
      assertThat(response.getStatus()).isEqualTo(201);
      assertThat(EntriesApi.text(response)).isEqualTo(original);
    }
    assertThat(responses.stream().filter(r -> r.getHeader(REPLAYED) != null).count()).isEqualTo(5);
  }

  @Test
  void concurrentRequestsWithTheSameKeyAndDifferentBodiesWriteOnceAndTheRest422() throws Exception {
    String key = key();
    List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
    for (int i = 1; i <= 4; i++) {
      Map<String, Object> body = with(expenseBody(actor), "valorCentavos", 100 + i);
      tasks.add(() -> postEntry(key, body));
    }

    List<MockHttpServletResponse> responses = runTogether(tasks);

    assertThat(entriesOf(actor.accountId())).isEqualTo(1);
    assertThat(responses.stream().filter(r -> r.getStatus() == 201).count()).isEqualTo(1);
    assertThat(responses.stream().filter(r -> r.getStatus() == 422).count()).isEqualTo(3);
  }

  @Test
  void aFailedRequestIsNotRememberedSoTheKeyCanBeReusedAfterTheFix() throws Exception {
    String key = key();
    MockHttpServletResponse bad =
        postEntry(key, with(expenseBody(actor), "contaId", UUID.randomUUID().toString()));
    MockHttpServletResponse fixed = postEntry(key, expenseBody(actor));

    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat(fixed.getStatus()).isEqualTo(201);
    assertThat(fixed.getHeader(REPLAYED)).isNull();
    assertThat(entriesOf(actor.accountId())).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"com espaço", "a/b", "ç", "x;y", " "})
  void rejectsAMalformedKey(String key) throws Exception {
    MockHttpServletResponse response = postEntry(key, expenseBody(actor));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"Idempotency-Key inválida\"}");
    assertThat(entriesOf(actor.accountId())).isZero();
  }

  @Test
  void rejectsAKeyOver80CharactersAndAcceptsExactly80() throws Exception {
    assertThat(postEntry("a".repeat(81), expenseBody(actor)).getStatus()).isEqualTo(400);
    assertThat(postEntry("a".repeat(80), expenseBody(actor)).getStatus()).isEqualTo(201);
  }

  @Test
  void rejectsTwoKeyHeaders() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/lancamentos")
                    .cookie(actor.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "um", "dois")
                    .content(EntriesApi.JSON.writeValueAsString(expenseBody(actor))))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void withoutASessionTheAnswerIsStill401() throws Exception {
    MockHttpServletResponse response = send("/lancamentos", null, key(), expenseBody(actor));

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void theHeaderIsIgnoredOnReads() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                get("/lancamentos?de=2026-01-01&ate=2026-01-31")
                    .cookie(actor.session())
                    .header("Idempotency-Key", "qualquer coisa com espaço"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void aStaleKeyInProgressFromACrashedRequestIsTakenOver() throws Exception {
    String key = key();
    insertInProgress(key, "/lancamentos", EntriesApi.JSON.writeValueAsString(expenseBody(actor)));
    jdbc.sql("UPDATE idempotencia SET criada_em = now() - interval '2 minutes' WHERE chave = :k")
        .param("k", key)
        .update();

    MockHttpServletResponse response = postEntry(key, expenseBody(actor));

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(entriesOf(actor.accountId())).isEqualTo(1);
  }

  @Test
  void aFreshKeyInProgressMakesTheCallerWaitAndThen409() throws Exception {
    String key = key();
    insertInProgress(key, "/lancamentos", EntriesApi.JSON.writeValueAsString(expenseBody(actor)));
    clock.freeze(Instant.now());

    CompletableFuture<MockHttpServletResponse> pending =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return postEntry(key, expenseBody(actor));
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            },
            pool);
    Thread.sleep(500);
    assertThat(pending).isNotDone();
    clock.advance(Duration.ofMinutes(1));
    MockHttpServletResponse response = pending.get(10, TimeUnit.SECONDS);

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(EntriesApi.text(response))
        .isEqualTo("{\"erro\":\"requisição com esta Idempotency-Key ainda em andamento\"}");
    assertThat(response.getHeader("Retry-After")).isNotNull();
    assertThat(entriesOf(actor.accountId())).isZero();
  }

  @Test
  void anInstallmentPurchaseAndATransferAlsoReplay() throws Exception {
    InstallmentsApi installments = new InstallmentsApi(mvc);
    InstallmentsApi.Shopper shopper = installments.newShopper();
    String purchaseKey = key();
    Map<String, Object> purchase = InstallmentsApi.purchaseBody(shopper);
    MockHttpServletResponse first =
        send("/compras-parceladas", shopper.session(), purchaseKey, purchase);
    MockHttpServletResponse again =
        send("/compras-parceladas", shopper.session(), purchaseKey, purchase);

    String other = api.newAccount(actor.session(), "Destino " + UUID.randomUUID());
    String transferKey = key();
    Map<String, Object> transfer = TransfersApi.body(actor.accountId(), other, 500);
    MockHttpServletResponse sent = send("/transferencias", actor.session(), transferKey, transfer);
    MockHttpServletResponse sentAgain =
        send("/transferencias", actor.session(), transferKey, transfer);

    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(again.getStatus()).isEqualTo(201);
    assertThat(again.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(first.getContentAsString(StandardCharsets.UTF_8));
    assertThat(sent.getStatus()).isEqualTo(201);
    assertThat(EntriesApi.text(sentAgain)).isEqualTo(EntriesApi.text(sent));
    assertThat(
            jdbc.sql("SELECT count(*) FROM transferencia WHERE usuario_id = :u")
                .param("u", actor.user().id())
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT count(*) FROM lancamento WHERE conta_id = :c")
                .param("c", UUID.fromString(shopper.cardId()))
                .query(Long.class)
                .single())
        .isEqualTo(3);
  }

  private void insertInProgress(String key, String route, String body) throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    String hash =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    jdbc.sql(
            "INSERT INTO idempotencia (usuario_id, chave, rota, corpo_hash)"
                + " VALUES (:u, :k, :r, :h)")
        .param("u", actor.user().id())
        .param("k", key)
        .param("r", route)
        .param("h", hash)
        .update();
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
}
