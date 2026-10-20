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

// Faixa de ±9007199254740991 centavos (ADR-029). Cada conta cabe sozinha, a soma pode não caber.
@IntegrationTest
class ProjectionLimitTest {

  private static final long MAX_CENTS = 9_007_199_254_740_991L;
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);
  private static final String OUT_OF_RANGE = "{\"erro\":\"projeção fora do limite\"}";

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

  @Test
  void twoAccountsNearTheCeilingPushTheSumOutOfRange() throws Exception {
    fixture.account(actor, MAX_CENTS);
    fixture.account(actor, 10_00);

    MockHttpServletResponse response = fixture.projection(actor.session(), "?dias=3");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(ProjectionFixture.text(response)).isEqualTo(OUT_OF_RANGE);
  }

  @Test
  void aSingleAccountExactlyAtTheCeilingStillFits() throws Exception {
    UUID account = fixture.account(actor, MAX_CENTS);

    MockHttpServletResponse response =
        fixture.projection(actor.session(), "?dias=2&contaId=" + account);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(ProjectionFixture.body(response).get("saldoAtualCentavos").asLong())
        .isEqualTo(MAX_CENTS);
  }

  @Test
  void aPlannedEntryThatPushesALaterDayOverTheCeilingIsRefused() throws Exception {
    UUID account = fixture.account(actor, MAX_CENTS - 5_00);
    fixture.income(actor, account, "previsto", 10_00, TODAY.plusDays(2));

    MockHttpServletResponse response = fixture.projection(actor.session(), "?dias=5");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(ProjectionFixture.text(response)).isEqualTo(OUT_OF_RANGE);
  }

  @Test
  void aSumBelowTheNegativeCeilingIsRefusedToo() throws Exception {
    fixture.account(actor, -MAX_CENTS);
    fixture.account(actor, -10_00);

    MockHttpServletResponse response = fixture.projection(actor.session(), "?dias=2");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(ProjectionFixture.text(response)).isEqualTo(OUT_OF_RANGE);
  }
}
