package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// PATCH /lancamentos/{id} confirmando com valor e data ajustados (ADR-028). Hoje fixo em
// 2026-10-19; a série mensal do dia 5 materializa de 2026-11-05 a 2027-10-05, a 1.500,00.
@IntegrationTest
class OccurrenceAdjustTest {

  private static final LocalDate FIRST = LocalDate.of(2026, 11, 5);
  private static final long CEILING = 9_007_199_254_740_991L;
  private static final String CANCEL_WITH_ADJUST =
      "{\"erro\":\"cancelar não aceita valorCentavos nem data\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
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
    pool = Executors.newFixedThreadPool(4);
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
    actor = api.newActor();
    templateId = createTemplate(monthlyBody(actor));
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    clock.reset();
  }

  private String createTemplate(Map<String, Object> body) throws Exception {
    return api.createOk(actor.session(), body).get("recorrencia").get("id").asString();
  }

  private String occurrenceId(String template, LocalDate key) {
    return db.byKey(template, key).orElseThrow().id();
  }

  private BigDecimal storedAmount(String id) {
    return jdbc.sql("SELECT valor FROM lancamento WHERE id = :id")
        .param("id", UUID.fromString(id))
        .query(BigDecimal.class)
        .single();
  }

  private LocalDate[] storedDates(String id) {
    return jdbc.sql("SELECT data_competencia, data_caixa FROM lancamento WHERE id = :id")
        .param("id", UUID.fromString(id))
        .query(
            (rs, row) ->
                new LocalDate[] {
                  rs.getObject("data_competencia", LocalDate.class),
                  rs.getObject("data_caixa", LocalDate.class)
                })
        .single();
  }

  private void assertUntouched(String id) {
    assertThat(db.byKey(templateId, FIRST).orElseThrow().status()).isEqualTo("previsto");
    assertThat(db.byKey(templateId, FIRST).orElseThrow().detached()).isFalse();
    assertThat(storedAmount(id)).isEqualByComparingTo("1500.00");
    assertThat(storedDates(id)).containsExactly(FIRST, FIRST);
  }

  private boolean listedBetween(String id, String from, String to) throws Exception {
    JsonNode list = RecurrencesApi.body(api.listEntries(actor.session(), from, to));
    for (JsonNode item : list) {
      if (item.get("id").asString().equals(id)) {
        return true;
      }
    }
    return false;
  }

  @Test
  void confirmingWithANewAmountCountsTheNewAmountInTheBalance() throws Exception {
    String id = occurrenceId(templateId, FIRST);

    MockHttpServletResponse response =
        api.confirmAdjusted(actor.session(), id, Map.of("valorCentavos", 90_000));

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = RecurrencesApi.body(response);
    assertThat(body.get("status").asString()).isEqualTo("realizado");
    assertThat(body.get("valorCentavos").asLong()).isEqualTo(90_000);
    assertThat(body.get("dataCaixa").asString()).isEqualTo("2026-11-05");
    assertThat(storedAmount(id)).isEqualByComparingTo("900.00");
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-90_000);
    var row = db.byKey(templateId, FIRST).orElseThrow();
    assertThat(row.detached()).isTrue();
    assertThat(row.occurrenceDate()).isEqualTo(FIRST);
  }

  @Test
  void confirmingWithANewDateMovesBothDatesAndTheListing() throws Exception {
    String id = occurrenceId(templateId, FIRST);
    assertThat(listedBetween(id, "2026-11-01", "2026-11-30")).isTrue();

    MockHttpServletResponse response =
        api.confirmAdjusted(actor.session(), id, Map.of("data", "2026-10-25"));

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = RecurrencesApi.body(response);
    assertThat(body.get("dataCompetencia").asString()).isEqualTo("2026-10-25");
    assertThat(body.get("dataCaixa").asString()).isEqualTo("2026-10-25");
    assertThat(body.get("valorCentavos").asLong()).isEqualTo(150_000);
    assertThat(storedDates(id))
        .containsExactly(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25));
    assertThat(listedBetween(id, "2026-11-01", "2026-11-30")).isFalse();
    assertThat(listedBetween(id, "2026-10-01", "2026-10-31")).isTrue();
    assertThat(db.byKey(templateId, FIRST).orElseThrow().occurrenceDate()).isEqualTo(FIRST);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-150_000);
  }

  @Test
  void confirmingWithAmountAndDateTogetherAppliesBoth() throws Exception {
    String id = occurrenceId(templateId, FIRST);

    MockHttpServletResponse response =
        api.confirmAdjusted(
            actor.session(), id, Map.of("valorCentavos", 123_456, "data", "2026-11-07"));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(storedAmount(id)).isEqualByComparingTo("1234.56");
    assertThat(storedDates(id))
        .containsExactly(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 7));
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-123_456);
  }

  @Test
  void confirmingWithoutAdjustmentKeepsTheF5Behavior() throws Exception {
    String id = occurrenceId(templateId, FIRST);

    MockHttpServletResponse response = api.confirm(actor.session(), id);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(storedAmount(id)).isEqualByComparingTo("1500.00");
    assertThat(storedDates(id)).containsExactly(FIRST, FIRST);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-150_000);
  }

  @Test
  void theJobDoesNotRecreateTheOccurrenceAtItsOriginalDateAfterAMovedConfirm() throws Exception {
    String id = occurrenceId(templateId, FIRST);
    api.confirmAdjusted(actor.session(), id, Map.of("data", "2026-10-25"));
    long before = db.countOccurrences(templateId);

    clock.advance(Duration.ofDays(1));
    job.run();
    job.run();

    assertThat(db.countOccurrences(templateId)).isEqualTo(before);
    assertThat(db.byKey(templateId, FIRST).orElseThrow().id()).isEqualTo(id);
  }

  @Test
  void anAdjustmentThatBreaksTheCeilingIsRejectedAndNothingChanges() throws Exception {
    Map<String, Object> small =
        with(with(monthlyBody(actor), "tipo", "receita"), "categoriaId", actor.incomeCategoryId());
    String income = createTemplate(with(small, "valorCentavos", 100));
    String first = occurrenceId(income, FIRST);
    String second = occurrenceId(income, LocalDate.of(2026, 12, 5));
    assertThat(
            api.confirmAdjusted(actor.session(), first, Map.of("valorCentavos", CEILING - 50))
                .getStatus())
        .isEqualTo(200);

    MockHttpServletResponse tooBig =
        api.confirmAdjusted(actor.session(), second, Map.of("valorCentavos", 51));

    assertThat(tooBig.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(tooBig))
        .isEqualTo("{\"erro\":\"saldo da conta fora do limite\"}");
    assertThat(db.byKey(income, LocalDate.of(2026, 12, 5)).orElseThrow().status())
        .isEqualTo("previsto");
    assertThat(db.byKey(income, LocalDate.of(2026, 12, 5)).orElseThrow().detached()).isFalse();
    assertThat(storedAmount(second)).isEqualByComparingTo("1.00");
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(CEILING - 50);

    // O original (1,00) também estouraria o teto; passar com 40 prova que vale o valor novo.
    MockHttpServletResponse fits =
        api.confirmAdjusted(actor.session(), second, Map.of("valorCentavos", 40));
    assertThat(fits.getStatus()).isEqualTo(200);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(CEILING - 10);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"status\":\"realizado\",\"valorCentavos\":0}",
        "{\"status\":\"realizado\",\"valorCentavos\":-1}",
        "{\"status\":\"realizado\",\"valorCentavos\":12.5}",
        "{\"status\":\"realizado\",\"valorCentavos\":\"100\"}",
        "{\"status\":\"realizado\",\"valorCentavos\":9007199254740992}",
        "{\"status\":\"realizado\",\"data\":\"2026-02-30\"}",
        "{\"status\":\"realizado\",\"data\":\"2026-1-1\"}",
        "{\"status\":\"realizado\",\"data\":\"1899-12-31\"}",
        "{\"status\":\"realizado\",\"data\":\"2200-01-01\"}",
        "{\"status\":\"realizado\",\"data\":\"\"}",
        "{\"status\":\"realizado\",\"data\":20261105}",
        "{\"status\":\"realizado\",\"data\":[2026,11,5]}",
        "{\"status\":\"realizado\",\"valor\":1}"
      })
  void invalidAdjustmentsAreA400AndNothingChanges(String body) throws Exception {
    String id = occurrenceId(templateId, FIRST);

    MockHttpServletResponse response = api.patchEntry(actor.session(), id, body);

    assertThat(response.getStatus()).isEqualTo(400);
    assertUntouched(id);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isZero();
  }

  @Test
  void theAmountAndDateMessagesAreTheSameAsTheEntryOnes() throws Exception {
    String id = occurrenceId(templateId, FIRST);

    assertThat(
            RecurrencesApi.text(
                api.patchEntry(
                    actor.session(), id, "{\"status\":\"realizado\",\"valorCentavos\":0}")))
        .isEqualTo("{\"erro\":\"valorCentavos deve ser um inteiro entre 1 e 9007199254740991\"}");
    assertThat(
            RecurrencesApi.text(
                api.patchEntry(
                    actor.session(), id, "{\"status\":\"realizado\",\"data\":\"2026-02-30\"}")))
        .isEqualTo(
            "{\"erro\":\"data deve estar no formato AAAA-MM-DD, com ano entre 1900 e 2199\"}");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"status\":\"cancelado\",\"valorCentavos\":100}",
        "{\"status\":\"cancelado\",\"data\":\"2026-11-06\"}",
        "{\"status\":\"cancelado\",\"valorCentavos\":100,\"data\":\"2026-11-06\"}"
      })
  void cancellingWithAmountOrDateIsA400AndNothingChanges(String body) throws Exception {
    String id = occurrenceId(templateId, FIRST);

    MockHttpServletResponse response = api.patchEntry(actor.session(), id, body);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response)).isEqualTo(CANCEL_WITH_ADJUST);
    assertUntouched(id);
  }

  @Test
  void confirmingAnAdjustedOccurrenceInAnArchivedAccountIsRejectedAndNothingChanges()
      throws Exception {
    String id = occurrenceId(templateId, FIRST);
    api.setAccountArchived(actor.session(), actor.accountId(), true);

    MockHttpServletResponse response =
        api.confirmAdjusted(
            actor.session(), id, Map.of("valorCentavos", 100, "data", "2026-11-06"));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response)).isEqualTo("{\"erro\":\"conta arquivada\"}");
    assertUntouched(id);
  }

  @Test
  void aTransferLegStillAnswers400WithTheTransferMessage() throws Exception {
    String other = api.newAccount(actor.session(), "Destino " + UUID.randomUUID());
    MockHttpServletResponse created =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/transferencias")
                    .cookie(actor.session())
                    .contentType("application/json")
                    .content(
                        "{\"contaOrigemId\":\""
                            + actor.accountId()
                            + "\",\"contaDestinoId\":\""
                            + other
                            + "\",\"valorCentavos\":100,\"data\":\"2026-10-19\"}"))
            .andReturn()
            .getResponse();
    assertThat(created.getStatus()).isEqualTo(201);
    String leg =
        jdbc.sql("SELECT saida_id::text FROM transferencia WHERE usuario_id = :u")
            .param("u", actor.user().id())
            .query(String.class)
            .single();

    MockHttpServletResponse response =
        api.confirmAdjusted(actor.session(), leg, Map.of("valorCentavos", 50));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response))
        .isEqualTo("{\"erro\":\"use a rota de transferência\"}");
  }

  @Test
  void anotherUsersOccurrenceWithAdjustmentAnswers404AndNothingChanges() throws Exception {
    RecurrencesApi.Actor other = api.newActor();
    String id = occurrenceId(templateId, FIRST);

    MockHttpServletResponse response =
        api.confirmAdjusted(
            other.session(), id, Map.of("valorCentavos", 100, "data", "2026-11-06"));

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(RecurrencesApi.text(response)).isEqualTo("{\"erro\":\"não encontrado\"}");
    assertUntouched(id);
  }

  @Test
  void twoAdjustedConfirmsInParallelCountOnceWithTheWinnersAmount() throws Exception {
    String id = occurrenceId(templateId, FIRST);
    CyclicBarrier start = new CyclicBarrier(2);
    List<CompletableFuture<MockHttpServletResponse>> futures = new ArrayList<>();
    for (long amount : new long[] {70_000, 80_000}) {
      futures.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  start.await(10, TimeUnit.SECONDS);
                  return api.confirmAdjusted(actor.session(), id, Map.of("valorCentavos", amount));
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              },
              pool));
    }
    MockHttpServletResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
    MockHttpServletResponse second = futures.get(1).get(30, TimeUnit.SECONDS);

    assertThat(List.of(first.getStatus(), second.getStatus())).containsExactlyInAnyOrder(200, 400);
    MockHttpServletResponse winner = first.getStatus() == 200 ? first : second;
    long winnerAmount = RecurrencesApi.body(winner).get("valorCentavos").asLong();
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-winnerAmount);
    assertThat(storedAmount(id)).isEqualByComparingTo(BigDecimal.valueOf(winnerAmount, 2));
    assertThat(db.countByStatus(templateId, "realizado")).isEqualTo(1);
  }

  @Test
  void withoutASessionTheAnswerIs401() throws Exception {
    assertThat(
            api.confirmAdjusted(null, occurrenceId(templateId, FIRST), Map.of("valorCentavos", 1))
                .getStatus())
        .isEqualTo(401);
  }
}
