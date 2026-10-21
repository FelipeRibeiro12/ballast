package com.felipe.ballast.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.math.BigDecimal;
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

// ADR-034: o saldo projetado sem contaId soma as contas ativas menos as de benefício. Com contaId
// de uma conta de benefício, projeta só ela.
@IntegrationTest
class ProjectionBenefitTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private ProjectionFixture fixture;
  private ProjectionFixture.Actor actor;
  private UUID checking;
  private UUID benefit;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
    fixture = new ProjectionFixture(mvc, jdbc);
    actor = fixture.newActor();
    checking = fixture.account(actor, 1_000_00);
    benefit = benefitAccount(actor, 500_00);
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private UUID benefitAccount(ProjectionFixture.Actor who, long initialCents) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO conta (id, usuario_id, nome, tipo, saldo_inicial)
            VALUES (:id, :userId, :name, 'beneficio', :initial)
            """)
        .param("id", id)
        .param("userId", who.user().id())
        .param("name", "VR " + id)
        .param("initial", BigDecimal.valueOf(initialCents, 2))
        .update();
    return id;
  }

  @Test
  void theTotalIgnoresTheBenefitBalanceAndItsMovements() throws Exception {
    fixture.expense(actor, benefit, "realizado", 100_00, TODAY.minusDays(2));
    fixture.expense(actor, benefit, "previsto", 50_00, TODAY.plusDays(1));
    fixture.income(actor, benefit, "previsto", 70_00, TODAY.plusDays(2));
    fixture.expense(actor, checking, "previsto", 30_00, TODAY.plusDays(1));

    JsonNode body = fixture.projectionOk(actor.session(), "?dias=3");

    assertThat(body.get("saldoAtualCentavos").asLong()).isEqualTo(1_000_00);
    JsonNode days = body.get("dias");
    assertThat(days.get(0).get("saldoCentavos").asLong()).isEqualTo(1_000_00);
    assertThat(days.get(1).get("saidasCentavos").asLong()).isEqualTo(30_00);
    assertThat(days.get(1).get("saldoCentavos").asLong()).isEqualTo(970_00);
    assertThat(days.get(2).get("entradasCentavos").asLong()).isZero();
    assertThat(days.get(2).get("saldoCentavos").asLong()).isEqualTo(970_00);
    assertThat(body.get("contaId").isNull()).isTrue();
  }

  @Test
  void withTheBenefitAccountIdOnlyThatAccountIsProjected() throws Exception {
    fixture.expense(actor, benefit, "realizado", 100_00, TODAY.minusDays(2));
    fixture.expense(actor, benefit, "previsto", 50_00, TODAY.plusDays(1));
    fixture.income(actor, benefit, "previsto", 70_00, TODAY.plusDays(2));
    fixture.expense(actor, checking, "previsto", 30_00, TODAY.plusDays(1));

    JsonNode body = fixture.projectionOk(actor.session(), "?dias=3&contaId=" + benefit);

    assertThat(body.get("saldoAtualCentavos").asLong()).isEqualTo(400_00);
    JsonNode days = body.get("dias");
    assertThat(days.get(1).get("saidasCentavos").asLong()).isEqualTo(50_00);
    assertThat(days.get(1).get("saldoCentavos").asLong()).isEqualTo(350_00);
    assertThat(days.get(2).get("entradasCentavos").asLong()).isEqualTo(70_00);
    assertThat(days.get(2).get("saldoCentavos").asLong()).isEqualTo(420_00);
    assertThat(body.get("contaId").asString()).isEqualTo(benefit.toString());
  }

  @Test
  void anotherUsersBenefitAccountIsStillNotFound() throws Exception {
    ProjectionFixture.Actor other = fixture.newActor();
    UUID theirs = benefitAccount(other, 10_00);

    var response = fixture.projection(actor.session(), "?contaId=" + theirs);

    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void aUserWithOnlyABenefitAccountGetsAnEmptyTotal() throws Exception {
    ProjectionFixture.Actor only = fixture.newActor();
    benefitAccount(only, 800_00);

    JsonNode body = fixture.projectionOk(only.session(), "?dias=2");

    assertThat(body.get("saldoAtualCentavos").asLong()).isZero();
    assertThat(body.get("dias").get(0).get("saldoCentavos").asLong()).isZero();
  }
}
