package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// PUT e DELETE em ocorrência de template (ADR-026). Hoje fixo em 2026-10-19; a série mensal do
// dia 5 materializa de 2026-11-05 a 2027-10-05.
@IntegrationTest
class OccurrenceEditTest {

  private static final LocalDate FIRST = LocalDate.of(2026, 11, 5);
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
    templateId =
        api.createOk(actor.session(), monthlyBody(actor)).get("recorrencia").get("id").asString();
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private String occurrenceId(LocalDate key) {
    return db.byKey(templateId, key).orElseThrow().id();
  }

  private Map<String, Object> putBody(RecurrencesApi.Actor who, String date, String title) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tipo", "despesa");
    body.put("titulo", title);
    body.put("valorCentavos", 150_000);
    body.put("categoriaId", who.expenseCategoryId());
    body.put("contaId", who.accountId());
    body.put("data", date);
    return body;
  }

  @Test
  void putOnAPlannedOccurrenceMovesTheDateAndDetachesIt() throws Exception {
    MockHttpServletResponse response =
        api.putEntry(actor.session(), occurrenceId(FIRST), putBody(actor, "2026-11-20", "Editado"));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(RecurrencesApi.body(response).get("status").asString()).isEqualTo("previsto");
    assertThat(RecurrencesApi.body(response).get("templateId").asString()).isEqualTo(templateId);
    RecurrenceTestSupport.Row row = db.byKey(templateId, FIRST).orElseThrow();
    assertThat(row.detached()).isTrue();
    assertThat(row.cashDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    assertThat(row.occurrenceDate()).isEqualTo(FIRST);
    assertThat(row.status()).isEqualTo("previsto");
  }

  @Test
  void theJobDoesNotCreateAnotherOnTheOriginalDateNorOverwriteTheEditedOne() throws Exception {
    api.putEntry(actor.session(), occurrenceId(FIRST), putBody(actor, "2026-11-20", "Editado"));
    long before = db.countOccurrences(templateId);

    clock.advance(Duration.ofDays(1));
    job.run();
    job.run();

    assertThat(db.countOccurrences(templateId)).isEqualTo(before);
    RecurrenceTestSupport.Row row = db.byKey(templateId, FIRST).orElseThrow();
    assertThat(row.title()).isEqualTo("Editado");
    assertThat(row.cashDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    assertThat(row.detached()).isTrue();
  }

  @Test
  void putOnAPlannedOccurrenceDoesNotCheckTheBalanceCeiling() throws Exception {
    // A conta já está no teto; uma prevista de teto não conta, então editar não estoura.
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/lancamentos")
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
        .andReturn();
    Map<String, Object> body = putBody(actor, "2026-11-05", "Receita enorme");
    body.put("tipo", "receita");
    body.put("categoriaId", actor.incomeCategoryId());
    body.put("valorCentavos", CEILING);

    MockHttpServletResponse response = api.putEntry(actor.session(), occurrenceId(FIRST), body);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(CEILING);
  }

  @Test
  void putMovingAPlannedOccurrenceToAnArchivedAccountIsRejected() throws Exception {
    String other = api.newAccount(actor.session(), "Outra " + System.nanoTime());
    api.setAccountArchived(actor.session(), other, true);
    Map<String, Object> body = with(putBody(actor, "2026-11-05", "Aluguel"), "contaId", other);

    MockHttpServletResponse response = api.putEntry(actor.session(), occurrenceId(FIRST), body);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response)).isEqualTo("{\"erro\":\"conta arquivada\"}");
    assertThat(db.byKey(templateId, FIRST).orElseThrow().detached()).isFalse();
  }

  @Test
  void putOnAConfirmedOccurrenceFollowsTheCommonRulesAndDetaches() throws Exception {
    String id = occurrenceId(FIRST);
    api.confirm(actor.session(), id);
    Map<String, Object> body =
        with(putBody(actor, "2026-11-06", "Aluguel"), "valorCentavos", 200_000);

    MockHttpServletResponse response = api.putEntry(actor.session(), id, body);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(RecurrencesApi.body(response).get("status").asString()).isEqualTo("realizado");
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-200_000);
    assertThat(db.byKey(templateId, FIRST).orElseThrow().detached()).isTrue();
  }

  @Test
  void deleteOnAPlannedOccurrenceIs400AndNothingChanges() throws Exception {
    MockHttpServletResponse response = api.deleteEntry(actor.session(), occurrenceId(FIRST));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response)).isEqualTo("{\"erro\":\"cancele a ocorrência\"}");
    assertThat(db.countOccurrences(templateId)).isEqualTo(12);
    assertThat(db.byKey(templateId, FIRST).orElseThrow().status()).isEqualTo("previsto");
  }

  @Test
  void deleteOnAPlannedOccurrenceOfAnotherUserStaysA404() throws Exception {
    RecurrencesApi.Actor other = api.newActor();

    MockHttpServletResponse response = api.deleteEntry(other.session(), occurrenceId(FIRST));

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(RecurrencesApi.text(response)).isEqualTo("{\"erro\":\"não encontrado\"}");
  }

  @Test
  void deletingAConfirmedPastOccurrenceIsNotRecreatedByTheJob() throws Exception {
    // Dia 5 de outubro já passou: a regra gera essa data, mas a materialização só olha de hoje.
    LocalDate past = LocalDate.of(2026, 10, 5);
    db.insertOccurrence(templateId, past, "realizado", false);

    assertThat(api.deleteEntry(actor.session(), occurrenceId(past)).getStatus()).isEqualTo(204);
    clock.advance(Duration.ofDays(1));
    job.run();

    assertThat(db.byKey(templateId, past)).isEmpty();
  }

  @Test
  void deletingAConfirmedFutureOccurrenceHandsTheSlotBackAndTheJobRecreatesItPlanned()
      throws Exception {
    String id = occurrenceId(FIRST);
    api.confirm(actor.session(), id);

    assertThat(api.deleteEntry(actor.session(), id).getStatus()).isEqualTo(204);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isZero();
    assertThat(db.byKey(templateId, FIRST)).isEmpty();
    job.run();

    RecurrenceTestSupport.Row again = db.byKey(templateId, FIRST).orElseThrow();
    assertThat(again.status()).isEqualTo("previsto");
    assertThat(again.detached()).isFalse();
    assertThat(again.id()).isNotEqualTo(id);
  }

  @Test
  void deletingACancelledFutureOccurrenceBehavesTheSame() throws Exception {
    String id = occurrenceId(FIRST);
    api.cancel(actor.session(), id);

    assertThat(api.deleteEntry(actor.session(), id).getStatus()).isEqualTo(204);
    assertThat(db.byKey(templateId, FIRST)).isEmpty();
    job.run();

    assertThat(db.byKey(templateId, FIRST).orElseThrow().status()).isEqualTo("previsto");
  }

  @Test
  void deletingAConfirmedFutureOccurrenceOfAnInactiveTemplateIsNotRecreated() throws Exception {
    String id = occurrenceId(FIRST);
    api.confirm(actor.session(), id);
    api.deactivate(actor.session(), templateId);

    assertThat(api.deleteEntry(actor.session(), id).getStatus()).isEqualTo(204);
    job.run();

    assertThat(db.byKey(templateId, FIRST)).isEmpty();
  }
}
