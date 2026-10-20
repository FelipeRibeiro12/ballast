package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.rule;
import static com.felipe.ballast.recurrences.RecurrencesApi.with;
import static com.felipe.ballast.recurrences.RecurrencesApi.withRule;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Duration;
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
import org.springframework.test.web.servlet.MockMvc;

// O job roda só quando o teste chama run: o scheduling está desligado no @IntegrationTest.
@IntegrationTest
class RecurrenceJobTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  @Autowired RecurrenceJob job;
  private RecurrencesApi api;
  private RecurrenceTestSupport db;
  private RecurrencesApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new RecurrencesApi(mvc);
    db = new RecurrenceTestSupport(jdbc);
    actor = api.newActor();
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
  void runningTheJobRightAfterTheCreationAddsNothing() throws Exception {
    String id = createId(actor, monthlyBody(actor));

    job.run();
    job.run();

    assertThat(db.countOccurrences(id)).isEqualTo(12);
  }

  @Test
  void theJobRunTwiceOnTheSameDayLeavesTheSameRows() throws Exception {
    String id = createId(actor, monthlyBody(actor));
    clock.advance(Duration.ofDays(31));
    job.run();
    long afterFirst = db.countOccurrences(id);
    List<LocalDate> datesAfterFirst = db.occurrenceDates(id);

    job.run();

    assertThat(db.countOccurrences(id)).isEqualTo(afterFirst);
    assertThat(db.occurrenceDates(id)).isEqualTo(datesAfterFirst);
  }

  @Test
  void advancingAMonthAddsOnlyTheNewMonthOfTheHorizon() throws Exception {
    String id = createId(actor, monthlyBody(actor));
    assertThat(db.occurrenceDates(id).getLast()).isEqualTo(d("2027-10-05"));

    // 2026-11-19: o horizonte vai a 2027-11-19 e entra só o dia 5 de novembro de 2027.
    clock.advance(Duration.ofDays(31));
    job.run();

    List<LocalDate> dates = db.occurrenceDates(id);
    assertThat(dates).hasSize(13);
    assertThat(dates.getLast()).isEqualTo(d("2027-11-05"));
    assertThat(dates.getFirst()).isEqualTo(d("2026-11-05"));
  }

  @Test
  void aDailySeriesGainsOneDatePerDay() throws Exception {
    String id =
        createId(
            actor, withRule(monthlyBody(actor), rule("diaria", Map.of("inicio", "2026-10-19"))));
    assertThat(db.countOccurrences(id)).isEqualTo(366);

    clock.advance(Duration.ofDays(1));
    job.run();

    assertThat(db.countOccurrences(id)).isEqualTo(367);
    assertThat(db.occurrenceDates(id).getLast()).isEqualTo(d("2027-10-20"));
  }

  @Test
  void anEditedOccurrenceKeepsItsKeyAndIsNotGeneratedAgain() throws Exception {
    String id =
        createId(
            actor, withRule(monthlyBody(actor), rule("diaria", Map.of("inicio", "2026-10-19"))));
    // Linha desgarrada com data editada: a ocorrência da data original continua "ocupada".
    jdbc.sql(
            "UPDATE lancamento SET desgarrado = true, data_caixa = :date, data_competencia = :date"
                + " WHERE template_id = :id AND ocorrencia_data = :key")
        .param("date", d("2030-01-01"))
        .param("id", UUID.fromString(id))
        .param("key", d("2026-10-25"))
        .update();
    clock.advance(Duration.ofDays(1));

    job.run();

    assertThat(db.occurrenceDates(id)).containsOnlyOnce(d("2026-10-25"));
    assertThat(db.countOccurrences(id)).isEqualTo(367);
  }

  @Test
  void theJobGeneratesNothingForAnInactiveTemplate() throws Exception {
    String id =
        createId(
            actor, withRule(monthlyBody(actor), rule("diaria", Map.of("inicio", "2026-10-19"))));
    api.deactivate(actor.session(), id);
    long before = db.countOccurrences(id);
    clock.advance(Duration.ofDays(5));

    job.run();

    assertThat(db.countOccurrences(id)).isEqualTo(before);
  }

  @Test
  void theJobSkipsATemplateWhoseAccountIsArchivedAndResumesWhenItIsBack() throws Exception {
    String id =
        createId(
            actor, withRule(monthlyBody(actor), rule("diaria", Map.of("inicio", "2026-10-19"))));
    api.setAccountArchived(actor.session(), actor.accountId(), true);
    clock.advance(Duration.ofDays(1));

    job.run();

    assertThat(db.countOccurrences(id)).isEqualTo(366);

    api.setAccountArchived(actor.session(), actor.accountId(), false);
    job.run();

    assertThat(db.countOccurrences(id)).isEqualTo(367);
  }

  @Test
  void theJobKeepsEachTemplateInItsOwnersAccount() throws Exception {
    RecurrencesApi.Actor other = api.newActor();
    String mine =
        createId(
            actor, withRule(monthlyBody(actor), rule("diaria", Map.of("inicio", "2026-10-19"))));
    String theirs =
        createId(
            other,
            with(
                withRule(monthlyBody(other), rule("diaria", Map.of("inicio", "2026-10-19"))),
                "titulo",
                "Do outro"));
    clock.advance(Duration.ofDays(3));

    job.run();

    assertThat(db.countOccurrences(mine)).isEqualTo(369);
    assertThat(db.countOccurrences(theirs)).isEqualTo(369);
    long crossed =
        jdbc.sql(
                """
                SELECT count(*) FROM lancamento l
                JOIN template_lancamento t ON t.id = l.template_id
                JOIN conta c ON c.id = l.conta_id
                WHERE l.usuario_id <> t.usuario_id OR c.usuario_id <> l.usuario_id
                   OR l.conta_id <> t.conta_id
                """)
            .query(Long.class)
            .single();
    assertThat(crossed).isZero();
    assertThat(
            jdbc.sql("SELECT count(*) FROM lancamento WHERE template_id = :id AND usuario_id = :u")
                .param("id", UUID.fromString(mine))
                .param("u", actor.user().id())
                .query(Long.class)
                .single())
        .isEqualTo(369);
  }

  @Test
  void plannedEntriesNeverChangeTheBalance() throws Exception {
    long before = api.accountBalance(actor.session(), actor.accountId());
    createId(actor, withRule(monthlyBody(actor), rule("diaria", Map.of("inicio", "2026-10-19"))));
    // Um dia só: a sessão do teste vale 7 dias e o job ainda tem uma data nova para inserir.
    clock.advance(Duration.ofDays(1));

    job.run();

    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(before);
  }
}
