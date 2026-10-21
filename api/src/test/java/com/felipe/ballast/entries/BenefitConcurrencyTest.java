package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.expenseBody;
import static com.felipe.ballast.entries.EntriesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.accounts.BenefitApi;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Troca da lista contra lançamento de despesa na mesma conta (V12). Uma conexão do teste segura a
// trava da conta e as duas requisições entram na fila nesta ordem, então o resultado não depende
// de sorte: quem entra depois da troca é avaliado contra a lista nova.
@IntegrationTest
class BenefitConcurrencyTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired DataSource dataSource;
  private EntriesApi api;
  private BenefitApi benefits;
  private EntriesApi.Actor actor;
  private String food;
  private String market;
  private String benefit;
  private ExecutorService pool;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    benefits = new BenefitApi(mvc);
    actor = api.newActor();
    food = api.categoryId(actor.session(), "Alimentação");
    market = actor.expenseCategoryId();
    benefit = benefits.createOk(actor.session(), "VR", List.of(food, market));
    pool = Executors.newFixedThreadPool(4);
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  private MockHttpServletResponse postExpense(String categoryId) throws Exception {
    return api.create(
        actor.session(),
        with(with(expenseBody(actor), "contaId", benefit), "categoriaId", categoryId));
  }

  private long entriesOf() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE conta_id = :id")
        .param("id", UUID.fromString(benefit))
        .query(Long.class)
        .single();
  }

  @Test
  void anExpenseQueuedBehindTheListChangeIsEvaluatedAgainstTheNewList() throws Exception {
    try (Connection holder = dataSource.getConnection()) {
      holdAccount(holder);
      CompletableFuture<MockHttpServletResponse> changing =
          CompletableFuture.supplyAsync(
              () -> call(() -> benefits.putIds(actor.session(), benefit, List.of(food))), pool);
      awaitBlockedSessions(1);
      CompletableFuture<MockHttpServletResponse> posting =
          CompletableFuture.supplyAsync(() -> call(() -> postExpense(market)), pool);
      awaitBlockedSessions(2);

      holder.commit();

      assertThat(changing.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      MockHttpServletResponse post = posting.get(10, TimeUnit.SECONDS);
      assertThat(post.getStatus()).isEqualTo(400);
      assertThat(EntriesApi.text(post))
          .isEqualTo("{\"erro\":\"categoria não permitida nesta conta\"}");
      assertThat(entriesOf()).isZero();
    }
  }

  @Test
  void anExpenseQueuedAheadOfTheListChangeIsEvaluatedAgainstTheOldListAndStays() throws Exception {
    try (Connection holder = dataSource.getConnection()) {
      holdAccount(holder);
      CompletableFuture<MockHttpServletResponse> posting =
          CompletableFuture.supplyAsync(() -> call(() -> postExpense(market)), pool);
      awaitBlockedSessions(1);
      CompletableFuture<MockHttpServletResponse> changing =
          CompletableFuture.supplyAsync(
              () -> call(() -> benefits.putIds(actor.session(), benefit, List.of(food))), pool);
      awaitBlockedSessions(2);

      holder.commit();

      assertThat(posting.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
      assertThat(changing.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(entriesOf()).isEqualTo(1);
    }
  }

  @Test
  void twoListChangesInParallelLeaveExactlyOneOfTheTwoLists() throws Exception {
    try (Connection holder = dataSource.getConnection()) {
      holdAccount(holder);
      CompletableFuture<MockHttpServletResponse> first =
          CompletableFuture.supplyAsync(
              () -> call(() -> benefits.putIds(actor.session(), benefit, List.of(food))), pool);
      CompletableFuture<MockHttpServletResponse> second =
          CompletableFuture.supplyAsync(
              () -> call(() -> benefits.putIds(actor.session(), benefit, List.of(market))), pool);
      awaitBlockedSessions(2);

      holder.commit();

      assertThat(first.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(second.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    }
    long stored =
        jdbc.sql("SELECT count(*) FROM conta_categoria_permitida WHERE conta_id = :id")
            .param("id", UUID.fromString(benefit))
            .query(Long.class)
            .single();
    assertThat(stored).isEqualTo(1);
  }

  private void holdAccount(Connection holder) throws Exception {
    holder.setAutoCommit(false);
    try (var statement = holder.prepareStatement("SELECT id FROM conta WHERE id = ? FOR UPDATE")) {
      statement.setObject(1, UUID.fromString(benefit));
      statement.executeQuery().close();
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
