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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Transferência na projeção (ADR-025, ADR-029): a perna de entrada soma e a de saída subtrai,
// na conta de cada uma.
@IntegrationTest
class ProjectionTransferTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private ProjectionFixture fixture;
  private ProjectionFixture.Actor actor;
  private UUID checking;
  private UUID savings;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
    fixture = new ProjectionFixture(mvc, jdbc);
    actor = fixture.newActor();
    checking = fixture.account(actor, 1_000_00);
    savings = fixture.account(actor, 200_00);
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  @Test
  void aTransferBetweenTwoActiveAccountsNetsToZeroInTheTotal() throws Exception {
    fixture.transfer(actor, checking, savings, 300_00, TODAY.plusDays(1));

    JsonNode body = fixture.projectionOk(actor.session(), "?dias=3");

    for (JsonNode day : body.get("dias")) {
      assertThat(day.get("saldoCentavos").asLong()).isEqualTo(1_200_00);
    }
    assertThat(body.get("saldoAtualCentavos").asLong()).isEqualTo(1_200_00);
  }

  @Test
  void aTransferBetweenTwoActiveAccountsIsNotMovementInTheTotal() throws Exception {
    fixture.transfer(actor, checking, savings, 300_00, TODAY.plusDays(1));

    JsonNode body = fixture.projectionOk(actor.session(), "?dias=3");

    JsonNode day = body.get("dias").get(1);
    assertThat(day.get("entradasCentavos").asLong()).isZero();
    assertThat(day.get("saidasCentavos").asLong()).isZero();
    assertThat(day.get("saldoCentavos").asLong()).isEqualTo(1_200_00);
  }

  @Test
  void withAnAccountTheTransferCountsBySignOfThatAccountsLeg() throws Exception {
    fixture.transfer(actor, checking, savings, 300_00, TODAY.plusDays(1));

    JsonNode fromChecking = fixture.projectionOk(actor.session(), "?dias=3&contaId=" + checking);
    JsonNode fromSavings = fixture.projectionOk(actor.session(), "?dias=3&contaId=" + savings);

    JsonNode out = fromChecking.get("dias").get(1);
    assertThat(out.get("saidasCentavos").asLong()).isEqualTo(300_00);
    assertThat(out.get("entradasCentavos").asLong()).isZero();
    assertThat(out.get("saldoCentavos").asLong()).isEqualTo(700_00);
    JsonNode in = fromSavings.get("dias").get(1);
    assertThat(in.get("entradasCentavos").asLong()).isEqualTo(300_00);
    assertThat(in.get("saidasCentavos").asLong()).isZero();
    assertThat(in.get("saldoCentavos").asLong()).isEqualTo(500_00);
    assertThat(fromChecking.get("saldoAtualCentavos").asLong()).isEqualTo(700_00);
    assertThat(fromSavings.get("saldoAtualCentavos").asLong()).isEqualTo(500_00);
  }

  @Test
  void aTransferFromAnActiveToAnArchivedAccountLeavesTheTotal() throws Exception {
    UUID archived = fixture.account(actor, 50_00, true);
    fixture.transfer(actor, checking, archived, 400_00, TODAY.plusDays(1));

    JsonNode body = fixture.projectionOk(actor.session(), "?dias=3");

    assertThat(body.get("dias").get(0).get("saldoCentavos").asLong()).isEqualTo(1_200_00);
    assertThat(body.get("dias").get(1).get("saldoCentavos").asLong()).isEqualTo(800_00);
    assertThat(body.get("dias").get(1).get("saidasCentavos").asLong()).isEqualTo(400_00);
    assertThat(body.get("saldoAtualCentavos").asLong()).isEqualTo(800_00);
  }

  @Test
  void aTransferFromAnArchivedToAnActiveAccountEntersTheTotal() throws Exception {
    UUID archived = fixture.account(actor, 50_00, true);
    fixture.transfer(actor, archived, savings, 100_00, TODAY.plusDays(1));

    JsonNode body = fixture.projectionOk(actor.session(), "?dias=3");

    assertThat(body.get("dias").get(1).get("saldoCentavos").asLong()).isEqualTo(1_300_00);
    assertThat(body.get("dias").get(1).get("entradasCentavos").asLong()).isEqualTo(100_00);
  }
}
