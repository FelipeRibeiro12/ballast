package com.felipe.ballast.reports;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ReportAuthorizationTest {

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  private ReportsApi api;

  @BeforeEach
  void setUp() {
    clock.freeze(Instant.parse("2026-03-15T15:00:00Z"));
    api = new ReportsApi(mvc);
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  @Test
  void aUserDoesNotSeeTheEntriesOfAnother() throws Exception {
    ReportsApi.Actor ana = api.newActor();
    ReportsApi.Actor bia = api.newActor();
    api.expense(
        bia, bia.accountId(), api.categoryId(bia.session(), "Mercado"), 10_000, "2026-03-02");
    api.income(bia, api.categoryId(bia.session(), "Salário"), 500_000, "2026-03-05");

    JsonNode report = api.reportOk(ana.session(), "2026-03");

    assertThat(report.get("despesasPorCategoria")).isEmpty();
    assertThat(report.get("totalDespesasCentavos").asLong()).isZero();
    assertThat(report.get("receitasEDespesas").findValues("receitaCentavos"))
        .allMatch(value -> value.asLong() == 0);
    assertThat(report.get("receitasEDespesas").findValues("despesaCentavos"))
        .allMatch(value -> value.asLong() == 0);
  }

  @Test
  void theRouteNeedsASession() throws Exception {
    assertThat(api.report(null, "?mes=2026-03").getStatus()).isEqualTo(401);
  }
}
