package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.rule;
import static com.felipe.ballast.recurrences.RecurrencesApi.with;
import static com.felipe.ballast.recurrences.RecurrencesApi.withRule;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class RecurrenceReadAndDeactivateTest {

  private static final String NOT_FOUND = "{\"erro\":\"não encontrado\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private RecurrencesApi api;
  private RecurrenceTestSupport db;
  private RecurrencesApi.Actor actor;
  private RecurrencesApi.Actor other;

  @BeforeEach
  void setUp() throws Exception {
    api = new RecurrencesApi(mvc);
    db = new RecurrenceTestSupport(jdbc);
    actor = api.newActor();
    other = api.newActor();
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private static LocalDate d(String iso) {
    return LocalDate.parse(iso);
  }

  private String createId(RecurrencesApi.Actor who, Map<String, Object> body) throws Exception {
    return api.createOk(who.session(), body).get("recorrencia").get("id").asString();
  }

  @Test
  void listShowsActiveAndInactiveOrderedByTitleIgnoringCaseThenId() throws Exception {
    String beta = createId(actor, with(monthlyBody(actor), "titulo", "beta"));
    String alpha = createId(actor, with(monthlyBody(actor), "titulo", "Alpha"));
    String zeta = createId(actor, with(monthlyBody(actor), "titulo", "Zeta"));
    String twinA = createId(actor, with(monthlyBody(actor), "titulo", "gama"));
    String twinB = createId(actor, with(monthlyBody(actor), "titulo", "gama"));
    api.deactivate(actor.session(), zeta);
    createId(other, with(monthlyBody(other), "titulo", "Do outro"));

    JsonNode list = RecurrencesApi.body(api.list(actor.session()));

    List<String> ids = list.valueStream().map(item -> item.get("id").asString()).toList();
    List<String> twins = List.of(twinA, twinB).stream().sorted().toList();
    assertThat(ids).containsExactly(alpha, beta, twins.get(0), twins.get(1), zeta);
    assertThat(list.get(4).get("ativo").asBoolean()).isFalse();
  }

  @Test
  void listIsEmptyForAUserWithoutRecurrences() throws Exception {
    MockHttpServletResponse response = api.list(actor.session());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(RecurrencesApi.text(response)).isEqualTo("[]");
  }

  @Test
  void findReturnsTheSameBodyAsTheCreation() throws Exception {
    JsonNode created = api.createOk(actor.session(), monthlyBody(actor)).get("recorrencia");

    MockHttpServletResponse response = api.find(actor.session(), created.get("id").asString());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(RecurrencesApi.body(response)).isEqualTo(created);
  }

  @Test
  void anotherUsersInexistentAndMalformedIdsAnswerTheSame404() throws Exception {
    String id = createId(other, monthlyBody(other));

    for (String candidate :
        List.of(
            id, UUID.randomUUID().toString(), "não-é-uuid", "1-1-1-1-1", id.toUpperCase() + "x")) {
      MockHttpServletResponse found = api.find(actor.session(), candidate);
      MockHttpServletResponse patched = api.deactivate(actor.session(), candidate);

      assertThat(found.getStatus()).as(candidate).isEqualTo(404);
      assertThat(RecurrencesApi.text(found)).isEqualTo(NOT_FOUND);
      assertThat(patched.getStatus()).as(candidate).isEqualTo(404);
      assertThat(RecurrencesApi.text(patched)).isEqualTo(NOT_FOUND);
    }
    JsonNode untouched = RecurrencesApi.body(api.find(other.session(), id));
    assertThat(untouched.get("ativo").asBoolean()).isTrue();
    assertThat(db.countOccurrences(id)).isEqualTo(12);
  }

  @Test
  void withoutASessionEveryRouteIs401() throws Exception {
    String id = createId(actor, monthlyBody(actor));

    assertThat(api.list(null).getStatus()).isEqualTo(401);
    assertThat(api.find(null, id).getStatus()).isEqualTo(401);
    assertThat(api.deactivate(null, id).getStatus()).isEqualTo(401);
  }

  @Test
  void deactivatingDeletesOnlyPlannedNonDetachedOccurrencesFromTodayOn() throws Exception {
    String id = createId(actor, monthlyBody(actor));
    // O que a API sozinha não produz: uma data passada, uma desgarrada e uma realizada.
    db.insertOccurrence(id, d("2026-10-05"), "previsto", false);
    db.insertOccurrence(id, d("2026-10-25"), "previsto", true);
    db.insertOccurrence(id, d("2026-10-26"), "realizado", false);
    db.insertOccurrence(id, d("2026-10-27"), "cancelado", false);

    MockHttpServletResponse response = api.deactivate(actor.session(), id);

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = RecurrencesApi.body(response);
    assertThat(body.get("ativo").asBoolean()).isFalse();
    assertThat(body.get("proximaOcorrencia").asString()).isEqualTo("2026-10-25");
    assertThat(db.occurrenceDates(id))
        .containsExactly(d("2026-10-05"), d("2026-10-25"), d("2026-10-26"), d("2026-10-27"));
  }

  @Test
  void anOccurrenceExactlyOnTodayIsDeleted() throws Exception {
    String id =
        createId(
            actor,
            withRule(
                monthlyBody(actor),
                rule("mensal", Map.of("diaDoMes", 19, "inicio", "2026-01-01"))));
    assertThat(db.occurrenceDates(id).getFirst()).isEqualTo(d("2026-10-19"));

    api.deactivate(actor.session(), id);

    assertThat(db.countOccurrences(id)).isZero();
  }

  @Test
  void deactivatingTwiceIsIdempotent() throws Exception {
    String id = createId(actor, monthlyBody(actor));
    db.insertOccurrence(id, d("2026-10-05"), "previsto", false);

    MockHttpServletResponse first = api.deactivate(actor.session(), id);
    MockHttpServletResponse second = api.deactivate(actor.session(), id);

    assertThat(first.getStatus()).isEqualTo(200);
    assertThat(second.getStatus()).isEqualTo(200);
    assertThat(RecurrencesApi.body(second)).isEqualTo(RecurrencesApi.body(first));
    assertThat(db.occurrenceDates(id)).containsExactly(d("2026-10-05"));
  }

  @Test
  void deactivatingLeavesTheBalanceAndOtherRecurrencesAlone() throws Exception {
    String id = createId(actor, monthlyBody(actor));
    String neighbour = createId(actor, with(monthlyBody(actor), "titulo", "Outra"));
    long balance = api.accountBalance(actor.session(), actor.accountId());

    api.deactivate(actor.session(), id);

    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(balance);
    assertThat(db.countOccurrences(neighbour)).isEqualTo(12);
  }

  @Test
  void reactivatingIsRefused() throws Exception {
    String id = createId(actor, monthlyBody(actor));
    api.deactivate(actor.session(), id);

    MockHttpServletResponse response = api.setActive(actor.session(), id, "{\"ativo\":true}");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response)).isEqualTo("{\"erro\":\"reativar não é suportado\"}");
    assertThat(RecurrencesApi.body(api.find(actor.session(), id)).get("ativo").asBoolean())
        .isFalse();
  }

  @Test
  void invalidPatchBodiesAreRefused() throws Exception {
    String id = createId(actor, monthlyBody(actor));

    for (String body :
        List.of(
            "{}",
            "{\"ativo\":null}",
            "{\"ativo\":\"false\"}",
            "{\"ativo\":0}",
            "{\"ativo\":false,\"titulo\":\"x\"}")) {
      MockHttpServletResponse response = api.setActive(actor.session(), id, body);

      assertThat(response.getStatus()).as(body).isEqualTo(400);
    }
    assertThat(db.countOccurrences(id)).isEqualTo(12);
  }

  @Test
  void anInactiveRecurrenceHasNoNextOccurrence() throws Exception {
    String id = createId(actor, monthlyBody(actor));

    api.deactivate(actor.session(), id);

    assertThat(RecurrencesApi.body(api.find(actor.session(), id)).get("proximaOcorrencia").isNull())
        .isTrue();
  }
}
