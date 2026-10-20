package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
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

// PATCH /lancamentos/{id}: confirmar e cancelar uma ocorrência prevista (ADR-026). Hoje fixo em
// 2026-10-19; a série mensal do dia 5 materializa de 2026-11-05 a 2027-10-05.
@IntegrationTest
class OccurrenceStatusTest {

  private static final LocalDate FIRST = LocalDate.of(2026, 11, 5);
  private static final String NOT_PLANNED =
      "{\"erro\":\"só ocorrência prevista pode mudar de status\"}";
  private static final String NOT_FOUND = "{\"erro\":\"não encontrado\"}";
  private static final long CEILING = 9_007_199_254_740_991L;

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  @Autowired RecurrenceJob job;
  private RecurrencesApi api;
  private RecurrenceTestSupport db;
  private RecurrencesApi.Actor actor;
  private String templateId;

  @BeforeEach
  void setUp() throws Exception {
    api = new RecurrencesApi(mvc);
    db = new RecurrenceTestSupport(jdbc);
    actor = api.newActor();
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
    templateId = createTemplate(actor, monthlyBody(actor));
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private String createTemplate(RecurrencesApi.Actor who, Map<String, Object> body)
      throws Exception {
    return api.createOk(who.session(), body).get("recorrencia").get("id").asString();
  }

  private String occurrenceId(String template, LocalDate key) {
    return db.byKey(template, key).orElseThrow().id();
  }

  @Test
  void confirmingAPlannedOccurrenceMovesItIntoTheBalanceExactly() throws Exception {
    String id = occurrenceId(templateId, FIRST);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isZero();

    MockHttpServletResponse response = api.confirm(actor.session(), id);

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = RecurrencesApi.body(response);
    assertThat(body.get("id").asString()).isEqualTo(id);
    assertThat(body.get("status").asString()).isEqualTo("realizado");
    assertThat(body.get("templateId").asString()).isEqualTo(templateId);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-150_000);
    assertThat(db.byKey(templateId, FIRST).orElseThrow().detached()).isTrue();
  }

  @Test
  void confirmingTwiceFailsTheSecondTimeAndTheBalanceCountsOnce() throws Exception {
    String id = occurrenceId(templateId, FIRST);
    api.confirm(actor.session(), id);

    MockHttpServletResponse second = api.confirm(actor.session(), id);

    assertThat(second.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(second)).isEqualTo(NOT_PLANNED);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-150_000);
  }

  @Test
  void confirmingInAnArchivedAccountIsRejectedAndNothingChanges() throws Exception {
    String id = occurrenceId(templateId, FIRST);
    api.setAccountArchived(actor.session(), actor.accountId(), true);

    MockHttpServletResponse response = api.confirm(actor.session(), id);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response)).isEqualTo("{\"erro\":\"conta arquivada\"}");
    assertThat(db.byKey(templateId, FIRST).orElseThrow().status()).isEqualTo("previsto");
    assertThat(db.byKey(templateId, FIRST).orElseThrow().detached()).isFalse();
  }

  @Test
  void confirmingPastTheBalanceCeilingIsRejectedAndTheOccurrenceStaysPlanned() throws Exception {
    Map<String, Object> huge =
        with(with(monthlyBody(actor), "tipo", "receita"), "categoriaId", actor.incomeCategoryId());
    String bigTemplate = createTemplate(actor, with(huge, "valorCentavos", CEILING));
    String first = occurrenceId(bigTemplate, FIRST);
    String second = occurrenceId(bigTemplate, LocalDate.of(2026, 12, 5));
    assertThat(api.confirm(actor.session(), first).getStatus()).isEqualTo(200);

    MockHttpServletResponse response = api.confirm(actor.session(), second);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response))
        .isEqualTo("{\"erro\":\"saldo da conta fora do limite\"}");
    assertThat(db.byKey(bigTemplate, LocalDate.of(2026, 12, 5)).orElseThrow().status())
        .isEqualTo("previsto");
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(CEILING);
  }

  @Test
  void plannedAndCancelledOccurrencesStayOutOfTheBalanceAndOfTheCeilingCheck() throws Exception {
    Map<String, Object> huge =
        with(with(monthlyBody(actor), "tipo", "receita"), "categoriaId", actor.incomeCategoryId());
    String bigTemplate = createTemplate(actor, with(huge, "valorCentavos", CEILING));
    api.cancel(actor.session(), occurrenceId(bigTemplate, FIRST));
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isZero();

    // 11 previstas e 1 cancelada de teto valem zero; um lançamento de teto ainda cabe.
    MockHttpServletResponse created =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/lancamentos")
                    .cookie(actor.session())
                    .contentType("application/json")
                    .content(
                        "{\"tipo\":\"receita\",\"titulo\":\"x\",\"valorCentavos\":"
                            + CEILING
                            + ",\"categoriaId\":\""
                            + actor.incomeCategoryId()
                            + "\",\"contaId\":\""
                            + actor.accountId()
                            + "\",\"data\":\"2026-10-19\"}"))
            .andReturn()
            .getResponse();

    assertThat(created.getStatus()).isEqualTo(201);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(CEILING);
  }

  @Test
  void confirmingAnotherUsersOccurrenceAnswers404WithTheSameBody() throws Exception {
    RecurrencesApi.Actor other = api.newActor();
    String id = occurrenceId(templateId, FIRST);

    MockHttpServletResponse response = api.confirm(other.session(), id);

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(RecurrencesApi.text(response)).isEqualTo(NOT_FOUND);
    assertThat(api.cancel(other.session(), id).getStatus()).isEqualTo(404);
    assertThat(db.byKey(templateId, FIRST).orElseThrow().status()).isEqualTo("previsto");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "00000000-0000-0000-0000-000000000000",
        "nao-e-uuid",
        "0198E3A0-0000-7000-8000-000000000000"
      })
  void unknownOrNonCanonicalIdsAnswer404(String id) throws Exception {
    MockHttpServletResponse response = api.confirm(actor.session(), id);

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(RecurrencesApi.text(response)).isEqualTo(NOT_FOUND);
  }

  @Test
  void withoutASessionTheAnswerIs401() throws Exception {
    assertThat(api.confirm(null, occurrenceId(templateId, FIRST)).getStatus()).isEqualTo(401);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"status\":\"previsto\"}",
        "{\"status\":\"REALIZADO\"}",
        "{\"status\":\"\"}",
        "{\"status\":null}",
        "{}",
        "{\"status\":1}",
        "{\"status\":\"realizado\",\"contaId\":\"x\"}",
      })
  void anyOtherBodyIsA400AndNothingChanges(String body) throws Exception {
    String id = occurrenceId(templateId, FIRST);

    MockHttpServletResponse response = api.patchEntry(actor.session(), id, body);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(db.byKey(templateId, FIRST).orElseThrow().status()).isEqualTo("previsto");
  }

  @Test
  void theMessagesForAMissingAndAnInvalidStatusAreFixed() throws Exception {
    String id = occurrenceId(templateId, FIRST);

    assertThat(RecurrencesApi.text(api.patchEntry(actor.session(), id, "{}")))
        .isEqualTo("{\"erro\":\"status é obrigatório\"}");
    assertThat(
            RecurrencesApi.text(api.patchEntry(actor.session(), id, "{\"status\":\"previsto\"}")))
        .isEqualTo("{\"erro\":\"status deve ser realizado ou cancelado\"}");
  }

  @Test
  void cancellingLeavesTheBalanceAndMarksTheOccurrenceDetached() throws Exception {
    String id = occurrenceId(templateId, FIRST);

    MockHttpServletResponse response = api.cancel(actor.session(), id);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(RecurrencesApi.body(response).get("status").asString()).isEqualTo("cancelado");
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isZero();
    assertThat(db.byKey(templateId, FIRST).orElseThrow().detached()).isTrue();
  }

  @Test
  void cancellingTwiceOrConfirmingACancelledOneIsA400() throws Exception {
    String id = occurrenceId(templateId, FIRST);
    api.cancel(actor.session(), id);

    MockHttpServletResponse again = api.cancel(actor.session(), id);
    MockHttpServletResponse confirm = api.confirm(actor.session(), id);

    assertThat(again.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(again)).isEqualTo(NOT_PLANNED);
    assertThat(confirm.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(confirm)).isEqualTo(NOT_PLANNED);
    assertThat(db.byKey(templateId, FIRST).orElseThrow().status()).isEqualTo("cancelado");
  }

  @Test
  void cancellingWorksEvenWithTheAccountArchived() throws Exception {
    String id = occurrenceId(templateId, FIRST);
    api.setAccountArchived(actor.session(), actor.accountId(), true);

    assertThat(api.cancel(actor.session(), id).getStatus()).isEqualTo(200);
  }

  @Test
  void theJobDoesNotRecreateACancelledOrConfirmedOccurrence() throws Exception {
    String cancelled = occurrenceId(templateId, FIRST);
    String confirmed = occurrenceId(templateId, LocalDate.of(2026, 12, 5));
    api.cancel(actor.session(), cancelled);
    api.confirm(actor.session(), confirmed);
    long before = db.countOccurrences(templateId);

    clock.advance(Duration.ofDays(1));
    job.run();
    job.run();

    assertThat(db.countOccurrences(templateId)).isEqualTo(before);
    assertThat(db.byKey(templateId, FIRST).orElseThrow().status()).isEqualTo("cancelado");
    assertThat(db.byKey(templateId, LocalDate.of(2026, 12, 5)).orElseThrow().status())
        .isEqualTo("realizado");
  }

  @Test
  void deactivatingTheTemplateKeepsConfirmedAndCancelledOccurrences() throws Exception {
    api.confirm(actor.session(), occurrenceId(templateId, FIRST));
    api.cancel(actor.session(), occurrenceId(templateId, LocalDate.of(2026, 12, 5)));

    api.deactivate(actor.session(), templateId);

    assertThat(db.occurrenceDates(templateId)).containsExactly(FIRST, LocalDate.of(2026, 12, 5));
  }
}
