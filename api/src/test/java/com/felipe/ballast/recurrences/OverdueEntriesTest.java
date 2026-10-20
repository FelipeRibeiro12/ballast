package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// GET /lancamentos/vencidos (ADR-028). Hoje fixo em 2026-10-19 (12h em São Paulo).
@IntegrationTest
class OverdueEntriesTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private RecurrencesApi api;
  private RecurrenceTestSupport db;
  private RecurrencesApi.Actor actor;
  private String templateId;

  @BeforeEach
  void setUp() throws Exception {
    api = new RecurrencesApi(mvc);
    db = new RecurrenceTestSupport(jdbc);
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
    actor = api.newActor();
    templateId = createTemplate(actor);
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private String createTemplate(RecurrencesApi.Actor who) throws Exception {
    return api.createOk(who.session(), monthlyBody(who)).get("recorrencia").get("id").asString();
  }

  private JsonNode overdue(RecurrencesApi.Actor who) throws Exception {
    MockHttpServletResponse response = api.overdue(who.session());
    assertThat(response.getStatus()).isEqualTo(200);
    return RecurrencesApi.body(response);
  }

  private List<String> dates(JsonNode body) {
    List<String> result = new ArrayList<>();
    for (JsonNode item : body.get("lancamentos")) {
      result.add(item.get("dataCaixa").asString());
    }
    return result;
  }

  @Test
  void theRouteIsNotSwallowedByTheIdHandler() throws Exception {
    MockHttpServletResponse response = api.overdue(actor.session());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(RecurrencesApi.text(response)).isEqualTo("{\"lancamentos\":[],\"maisAlgum\":false}");
  }

  @Test
  void listsOnlyPlannedEntriesBeforeToday() throws Exception {
    db.insertOccurrence(templateId, LocalDate.of(2026, 10, 1), "previsto", false);
    db.insertOccurrence(templateId, LocalDate.of(2026, 10, 18), "previsto", false);
    db.insertOccurrence(templateId, TODAY, "previsto", false);
    db.insertOccurrence(templateId, LocalDate.of(2026, 10, 20), "previsto", false);
    db.insertOccurrence(templateId, LocalDate.of(2026, 9, 1), "realizado", false);
    db.insertOccurrence(templateId, LocalDate.of(2026, 9, 2), "cancelado", false);

    JsonNode body = overdue(actor);

    assertThat(dates(body)).containsExactly("2026-10-01", "2026-10-18");
    assertThat(body.get("maisAlgum").asBoolean()).isFalse();
    JsonNode first = body.get("lancamentos").get(0);
    assertThat(first.get("status").asString()).isEqualTo("previsto");
    assertThat(first.get("valorCentavos").asLong()).isEqualTo(150_000);
    assertThat(first.get("templateId").asString()).isEqualTo(templateId);
  }

  @Test
  void ordersByCashDateThenId() throws Exception {
    String other = createTemplate(actor);
    db.insertOccurrence(templateId, LocalDate.of(2026, 10, 10), "previsto", false);
    db.insertOccurrence(other, LocalDate.of(2026, 10, 10), "previsto", false);
    db.insertOccurrence(templateId, LocalDate.of(2026, 10, 2), "previsto", false);

    JsonNode body = overdue(actor);

    assertThat(dates(body)).containsExactly("2026-10-02", "2026-10-10", "2026-10-10");
    String firstId = body.get("lancamentos").get(1).get("id").asString();
    String secondId = body.get("lancamentos").get(2).get("id").asString();
    assertThat(firstId.compareTo(secondId)).isNegative();
  }

  @Test
  void usesTheSaoPauloDayAndNotTheUtcOne() throws Exception {
    // 22h30 de 19/10 em São Paulo já é 20/10 em UTC: a prevista de 19/10 ainda não venceu.
    clock.freeze(Instant.parse("2026-10-20T01:30:00Z"));
    db.insertOccurrence(templateId, TODAY, "previsto", false);

    assertThat(dates(overdue(actor))).isEmpty();
  }

  @Test
  void advancingTheClockMakesPlannedEntriesAppear() throws Exception {
    db.insertOccurrence(templateId, TODAY, "previsto", false);
    assertThat(dates(overdue(actor))).isEmpty();

    clock.advance(Duration.ofDays(1));

    assertThat(dates(overdue(actor))).containsExactly("2026-10-19");
  }

  @Test
  void cutsAt200AndFlagsThereIsMore() throws Exception {
    for (int day = 0; day < 201; day++) {
      db.insertOccurrence(templateId, LocalDate.of(2026, 1, 1).plusDays(day), "previsto", false);
    }

    JsonNode body = overdue(actor);

    assertThat(body.get("lancamentos")).hasSize(200);
    assertThat(body.get("maisAlgum").asBoolean()).isTrue();
    assertThat(dates(body).get(0)).isEqualTo("2026-01-01");
    assertThat(dates(body).get(199)).isEqualTo("2026-07-19");
  }

  @Test
  void exactly200HasNoMore() throws Exception {
    for (int day = 0; day < 200; day++) {
      db.insertOccurrence(templateId, LocalDate.of(2026, 1, 1).plusDays(day), "previsto", false);
    }

    JsonNode body = overdue(actor);

    assertThat(body.get("lancamentos")).hasSize(200);
    assertThat(body.get("maisAlgum").asBoolean()).isFalse();
  }

  @Test
  void neverShowsAnotherUsersEntries() throws Exception {
    RecurrencesApi.Actor other = api.newActor();
    String otherTemplate = createTemplate(other);
    db.insertOccurrence(otherTemplate, LocalDate.of(2026, 10, 1), "previsto", false);
    db.insertOccurrence(templateId, LocalDate.of(2026, 10, 2), "previsto", false);

    assertThat(dates(overdue(actor))).containsExactly("2026-10-02");
    assertThat(dates(overdue(other))).containsExactly("2026-10-01");
  }

  @Test
  void confirmingOrCancellingRemovesTheEntryFromTheList() throws Exception {
    db.insertOccurrence(templateId, LocalDate.of(2026, 10, 1), "previsto", false);
    db.insertOccurrence(templateId, LocalDate.of(2026, 10, 2), "previsto", false);
    JsonNode body = overdue(actor);
    String first = body.get("lancamentos").get(0).get("id").asString();
    String second = body.get("lancamentos").get(1).get("id").asString();

    assertThat(api.confirm(actor.session(), first).getStatus()).isEqualTo(200);
    assertThat(api.cancel(actor.session(), second).getStatus()).isEqualTo(200);

    assertThat(dates(overdue(actor))).isEmpty();
    assertThat(db.countOccurrences(templateId)).isGreaterThan(2);
  }

  @Test
  void withoutASessionTheAnswerIs401() throws Exception {
    assertThat(api.overdue(null).getStatus()).isEqualTo(401);
  }
}
