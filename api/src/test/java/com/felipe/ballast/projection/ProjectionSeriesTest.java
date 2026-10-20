package com.felipe.ballast.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// GET /saldo-projetado (ADR-029). Hoje fixo em 2026-10-19, meio-dia em São Paulo.
@IntegrationTest
class ProjectionSeriesTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private ProjectionFixture fixture;
  private ProjectionFixture.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
    fixture = new ProjectionFixture(mvc, jdbc);
    actor = fixture.newActor();
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private JsonNode project(String query) throws Exception {
    return fixture.projectionOk(actor.session(), query);
  }

  private static void assertDay(
      JsonNode day, String date, long inflow, long outflow, long balance) {
    assertThat(day.get("data").asString()).isEqualTo(date);
    assertThat(day.get("entradasCentavos").asLong()).as(date + " entradas").isEqualTo(inflow);
    assertThat(day.get("saidasCentavos").asLong()).as(date + " saidas").isEqualTo(outflow);
    assertThat(day.get("saldoCentavos").asLong()).as(date + " saldo").isEqualTo(balance);
  }

  @Test
  void anUserWithoutAccountsGetsAFlatSeriesOfZeros() throws Exception {
    JsonNode body = project("?dias=3");

    assertThat(body.get("dias")).hasSize(3);
    assertDay(body.get("dias").get(0), "2026-10-19", 0, 0, 0);
    assertDay(body.get("dias").get(2), "2026-10-21", 0, 0, 0);
    assertThat(body.get("saldoAtualCentavos").asLong()).isZero();
    assertThat(body.get("vencidasCentavos").asLong()).isZero();
  }

  @Test
  void theDefaultIs90DaysStartingTodayAndEndingOnTheLastPoint() throws Exception {
    JsonNode body = project("");

    assertThat(body.get("inicio").asString()).isEqualTo("2026-10-19");
    assertThat(body.get("fim").asString()).isEqualTo("2027-01-16");
    assertThat(body.get("dias")).hasSize(90);
    assertThat(body.get("dias").get(89).get("data").asString()).isEqualTo("2027-01-16");
    assertThat(body.get("contaId").isNull()).isTrue();
  }

  @Test
  void realizedTodayAndPlannedLaterBuildTheBalanceDayByDay() throws Exception {
    UUID account = fixture.account(actor, 1_000_00);
    fixture.income(actor, account, "realizado", 500_00, TODAY);
    fixture.expense(actor, account, "previsto", 200_00, TODAY.plusDays(10));
    fixture.income(actor, account, "previsto", 300_00, TODAY.plusDays(30));

    JsonNode body = project("?dias=40");

    JsonNode days = body.get("dias");
    assertThat(days).hasSize(40);
    assertDay(days.get(0), "2026-10-19", 500_00, 0, 1_500_00);
    assertDay(days.get(1), "2026-10-20", 0, 0, 1_500_00);
    assertDay(days.get(9), "2026-10-28", 0, 0, 1_500_00);
    assertDay(days.get(10), "2026-10-29", 0, 200_00, 1_300_00);
    assertDay(days.get(29), "2026-11-17", 0, 0, 1_300_00);
    assertDay(days.get(30), "2026-11-18", 300_00, 0, 1_600_00);
    assertDay(days.get(39), "2026-11-27", 0, 0, 1_600_00);
    assertThat(body.get("fim").asString()).isEqualTo("2026-11-27");
    assertThat(body.get("saldoAtualCentavos").asLong()).isEqualTo(1_500_00);
    assertThat(body.get("vencidasCentavos").asLong()).isZero();
  }

  @Test
  void anOverdueEntryCountsFromTheFirstDayAndShowsInTheOverdueTotal() throws Exception {
    UUID account = fixture.account(actor, 1_000_00);
    fixture.expense(actor, account, "previsto", 120_00, TODAY.minusDays(3));
    fixture.income(actor, account, "previsto", 50_00, TODAY.minusDays(1));

    JsonNode body = project("?dias=2");

    assertDay(body.get("dias").get(0), "2026-10-19", 0, 0, 1_000_00 - 120_00 + 50_00);
    assertThat(body.get("vencidasCentavos").asLong()).isEqualTo(-120_00 + 50_00);
    assertThat(body.get("saldoAtualCentavos").asLong()).isEqualTo(1_000_00);
  }

  @Test
  void theFirstPointShowsOnlyTodaysMovement() throws Exception {
    UUID account = fixture.account(actor, 0);
    fixture.income(actor, account, "realizado", 700_00, TODAY.minusDays(1));
    fixture.expense(actor, account, "realizado", 40_00, TODAY);

    JsonNode body = project("?dias=1");

    assertDay(body.get("dias").get(0), "2026-10-19", 0, 40_00, 660_00);
  }

  @Test
  void aCancelledEntryNeverCounts() throws Exception {
    UUID account = fixture.account(actor, 100_00);
    fixture.expense(actor, account, "cancelado", 90_00, TODAY.minusDays(2));
    fixture.expense(actor, account, "cancelado", 80_00, TODAY);
    fixture.income(actor, account, "cancelado", 70_00, TODAY.plusDays(1));

    JsonNode body = project("?dias=3");

    for (JsonNode day : body.get("dias")) {
      assertThat(day.get("saldoCentavos").asLong()).isEqualTo(100_00);
      assertThat(day.get("entradasCentavos").asLong()).isZero();
      assertThat(day.get("saidasCentavos").asLong()).isZero();
    }
    assertThat(body.get("vencidasCentavos").asLong()).isZero();
    assertThat(body.get("saldoAtualCentavos").asLong()).isEqualTo(100_00);
  }

  @Test
  void aRealizedEntryWithAFutureDateCountsOnItsDayNotToday() throws Exception {
    UUID account = fixture.account(actor, 1_000_00);
    fixture.income(actor, account, "realizado", 250_00, TODAY.plusDays(5));

    JsonNode body = project("?dias=7");

    assertDay(body.get("dias").get(0), "2026-10-19", 0, 0, 1_000_00);
    assertDay(body.get("dias").get(4), "2026-10-23", 0, 0, 1_000_00);
    assertDay(body.get("dias").get(5), "2026-10-24", 250_00, 0, 1_250_00);
    // O saldo de GET /contas soma todo realizado, de qualquer data (ADR-024).
    assertThat(body.get("saldoAtualCentavos").asLong()).isEqualTo(1_250_00);
    assertThat(fixture.accountBalance(actor.session(), account)).isEqualTo(1_250_00);
  }

  @Test
  void theCurrentBalanceIsTheSumOfTheAccountBalances() throws Exception {
    UUID first = fixture.account(actor, 300_00);
    UUID second = fixture.account(actor, 50_00);
    fixture.income(actor, first, "realizado", 10_00, TODAY.minusDays(9));
    fixture.expense(actor, second, "realizado", 5_00, TODAY.plusDays(400));
    fixture.expense(actor, second, "previsto", 99_00, TODAY.plusDays(2));

    JsonNode body = project("?dias=5");

    long expected =
        fixture.accountBalance(actor.session(), first)
            + fixture.accountBalance(actor.session(), second);
    assertThat(body.get("saldoAtualCentavos").asLong()).isEqualTo(expected).isEqualTo(355_00);
  }

  @Test
  void activeAccountsAreTheDefaultAndAnArchivedOneStaysOut() throws Exception {
    fixture.account(actor, 100_00);
    UUID archived = fixture.account(actor, 900_00, true);
    fixture.income(actor, archived, "previsto", 10_00, TODAY.plusDays(1));

    JsonNode total = project("?dias=3");
    JsonNode onlyArchived = project("?dias=3&contaId=" + archived);

    assertDay(total.get("dias").get(1), "2026-10-20", 0, 0, 100_00);
    assertThat(total.get("saldoAtualCentavos").asLong()).isEqualTo(100_00);
    assertThat(onlyArchived.get("contaId").asString()).isEqualTo(archived.toString());
    assertDay(onlyArchived.get("dias").get(1), "2026-10-20", 10_00, 0, 910_00);
  }

  @Test
  void theLowestBalanceIsTheFirstDayWhenTwoDaysTie() throws Exception {
    UUID account = fixture.account(actor, 100_00);
    fixture.expense(actor, account, "previsto", 60_00, TODAY.plusDays(1));
    fixture.income(actor, account, "previsto", 10_00, TODAY.plusDays(3));
    fixture.expense(actor, account, "previsto", 10_00, TODAY.plusDays(4));
    fixture.income(actor, account, "previsto", 500_00, TODAY.plusDays(6));

    JsonNode lowest = project("?dias=8").get("menorSaldo");

    // 40,00 no dia 1, 50,00 no dia 3, 40,00 de novo no dia 4: vale o primeiro.
    assertThat(lowest.get("data").asString()).isEqualTo("2026-10-20");
    assertThat(lowest.get("saldoCentavos").asLong()).isEqualTo(40_00);
  }

  @Test
  void aFlatSeriesHasTodayAsTheLowestBalance() throws Exception {
    fixture.account(actor, 77_00);

    JsonNode lowest = project("?dias=10").get("menorSaldo");

    assertThat(lowest.get("data").asString()).isEqualTo("2026-10-19");
    assertThat(lowest.get("saldoCentavos").asLong()).isEqualTo(77_00);
  }

  @Test
  void theLowestBalanceCanBeNegative() throws Exception {
    UUID account = fixture.account(actor, 10_00);
    fixture.expense(actor, account, "previsto", 30_00, TODAY.plusDays(2));

    JsonNode lowest = project("?dias=5").get("menorSaldo");

    assertThat(lowest.get("data").asString()).isEqualTo("2026-10-21");
    assertThat(lowest.get("saldoCentavos").asLong()).isEqualTo(-20_00);
  }

  @Test
  void todayIsTheSaoPauloDayNotTheUtcOne() throws Exception {
    // 22h30 do dia 19 em São Paulo já é 01h30 do dia 20 em UTC.
    clock.freeze(Instant.parse("2026-10-20T01:30:00Z"));
    UUID account = fixture.account(actor, 0);
    fixture.expense(actor, account, "previsto", 10_00, LocalDate.of(2026, 10, 19));

    JsonNode body = project("?dias=2");

    assertThat(body.get("inicio").asString()).isEqualTo("2026-10-19");
    assertDay(body.get("dias").get(0), "2026-10-19", 0, 10_00, -10_00);
    assertThat(body.get("vencidasCentavos").asLong()).isZero();
  }

  @Test
  void theResponseUsesIntegerCentsNotDecimals() throws Exception {
    UUID account = fixture.account(actor, 12_34);
    fixture.income(actor, account, "previsto", 5_67, TODAY.plusDays(1));

    MockHttpServletResponse response = fixture.projection(actor.session(), "?dias=2");

    assertThat(ProjectionFixture.text(response)).contains("\"saldoCentavos\":1801");
    assertThat(ProjectionFixture.text(response)).doesNotContain(".");
  }
}
