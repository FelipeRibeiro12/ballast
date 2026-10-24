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

// Hoje é 15 de março de 2026 em São Paulo.
@IntegrationTest
class SpendableTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private ProjectionFixture fixture;
  private ProjectionFixture.Actor actor;
  private UUID checking;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2026-03-15T15:00:00Z"));
    fixture = new ProjectionFixture(mvc, jdbc);
    actor = fixture.newActor();
    checking = fixture.account(actor, 1_000_00);
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  @Test
  void whatIsLeftIsTheLowestBalanceBeforeTheNextSalary() throws Exception {
    fixture.salary(actor, checking, "previsto", 5_000_00, LocalDate.of(2026, 4, 5));
    fixture.expense(actor, checking, "previsto", 300_00, LocalDate.of(2026, 3, 20));
    fixture.incomeIn(
        actor, checking, "Outras receitas", "previsto", 100_00, LocalDate.of(2026, 3, 22));
    fixture.expense(actor, checking, "previsto", 200_00, LocalDate.of(2026, 3, 25));
    // Depois do salário já é o mês seguinte do dinheiro: não conta.
    fixture.expense(actor, checking, "previsto", 4_000_00, LocalDate.of(2026, 4, 10));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("hoje").asString()).isEqualTo("2026-03-15");
    assertThat(body.get("ate").asString()).isEqualTo("2026-04-04");
    assertThat(body.get("proximoSalario").get("data").asString()).isEqualTo("2026-04-05");
    assertThat(body.get("proximoSalario").get("valorCentavos").asLong()).isEqualTo(5_000_00);
    // O menor saldo é o do dia 25: 1.000 - 300 + 100 - 200.
    assertThat(body.get("livreCentavos").asLong()).isEqualTo(600_00);
    assertThat(body.get("menorSaldoEm").asString()).isEqualTo("2026-03-25");
    assertThat(body.get("dias").asInt()).isEqualTo(21);
    // 600 / 21 = 28,571...: arredonda para baixo, para nunca prometer mais do que tem.
    assertThat(body.get("porDiaCentavos").asLong()).isEqualTo(28_57);
  }

  // O salário se acha pela marca da semente (ADR-057), não pelo nome.
  @Test
  void theSalaryIsFoundByTheSeedMarkEvenAfterARename() throws Exception {
    jdbc.sql("UPDATE categoria SET nome = 'Ordenado' WHERE usuario_id = :u AND semente = 'salario'")
        .param("u", actor.user().id())
        .update();
    fixture.incomeIn(actor, checking, "Ordenado", "previsto", 5_000_00, LocalDate.of(2026, 4, 5));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("proximoSalario").get("data").asString()).isEqualTo("2026-04-05");
  }

  @Test
  void aCategoryNamedSalarioWithoutTheMarkIsNotTheSalary() throws Exception {
    jdbc.sql("UPDATE categoria SET semente = NULL WHERE usuario_id = :u AND semente = 'salario'")
        .param("u", actor.user().id())
        .update();
    fixture.salary(actor, checking, "previsto", 5_000_00, LocalDate.of(2026, 4, 5));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("proximoSalario").isNull()).isTrue();
  }

  @Test
  void aLaterIncomeDoesNotCoverAnEarlierExpense() throws Exception {
    fixture.salary(actor, checking, "previsto", 5_000_00, LocalDate.of(2026, 4, 5));
    fixture.expense(actor, checking, "previsto", 900_00, LocalDate.of(2026, 3, 20));
    fixture.incomeIn(
        actor, checking, "Outras receitas", "previsto", 800_00, LocalDate.of(2026, 3, 30));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("livreCentavos").asLong()).isEqualTo(100_00);
    assertThat(body.get("menorSaldoEm").asString()).isEqualTo("2026-03-20");
  }

  @Test
  void aSalaryDueTodayIsAlreadyMoneyToSpend() throws Exception {
    fixture.salary(actor, checking, "previsto", 5_000_00, TODAY);
    fixture.salary(actor, checking, "previsto", 5_000_00, LocalDate.of(2026, 4, 15));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("proximoSalario").get("data").asString()).isEqualTo("2026-04-15");
    assertThat(body.get("livreCentavos").asLong()).isEqualTo(6_000_00);
  }

  @Test
  void withoutSalaryTheWindowEndsWithTheMonth() throws Exception {
    fixture.expense(actor, checking, "previsto", 100_00, LocalDate.of(2026, 3, 31));
    fixture.expense(actor, checking, "previsto", 500_00, LocalDate.of(2026, 4, 1));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("proximoSalario").isNull()).isTrue();
    assertThat(body.get("ate").asString()).isEqualTo("2026-03-31");
    assertThat(body.get("dias").asInt()).isEqualTo(17);
    assertThat(body.get("livreCentavos").asLong()).isEqualTo(900_00);
  }

  @Test
  void spendingMoreThanThereIsLeavesItNegativeAndNothingPerDay() throws Exception {
    fixture.salary(actor, checking, "previsto", 5_000_00, LocalDate.of(2026, 4, 5));
    fixture.expense(actor, checking, "previsto", 1_250_00, LocalDate.of(2026, 3, 28));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("livreCentavos").asLong()).isEqualTo(-250_00);
    assertThat(body.get("porDiaCentavos").isNull()).isTrue();
  }

  @Test
  void anOverdueExpectedEntryIsCountedAndReportedApart() throws Exception {
    fixture.expense(actor, checking, "previsto", 150_00, LocalDate.of(2026, 3, 10));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("livreCentavos").asLong()).isEqualTo(850_00);
    assertThat(body.get("vencidasCentavos").asLong()).isEqualTo(-150_00);
  }

  @Test
  void aBenefitStaysOutOfTheTotalAndIsShownOnItsOwn() throws Exception {
    UUID meal = fixture.benefitAccount(actor, "VR", 600_00);
    fixture.expense(actor, meal, "previsto", 50_00, LocalDate.of(2026, 3, 18));
    // Recarga do benefício não é salário: o salário só vale em conta que não é de benefício.
    fixture.salary(actor, meal, "previsto", 700_00, LocalDate.of(2026, 3, 20));
    fixture.salary(actor, checking, "previsto", 5_000_00, LocalDate.of(2026, 4, 5));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("proximoSalario").get("data").asString()).isEqualTo("2026-04-05");
    assertThat(body.get("livreCentavos").asLong()).isEqualTo(1_000_00);
    JsonNode benefits = body.get("beneficios");
    assertThat(benefits).hasSize(1);
    assertThat(benefits.get(0).get("contaId").asString()).isEqualTo(meal.toString());
    assertThat(benefits.get(0).get("nome").asString()).isEqualTo("VR");
    // O menor saldo do VR até o salário: 600 - 50 antes da recarga.
    assertThat(benefits.get(0).get("livreCentavos").asLong()).isEqualTo(550_00);
  }

  @Test
  void aUserDoesNotSeeTheMoneyOfAnother() throws Exception {
    ProjectionFixture.Actor other = fixture.newActor();
    UUID otherChecking = fixture.account(other, 9_999_00);
    fixture.salary(other, otherChecking, "previsto", 1_00, LocalDate.of(2026, 3, 18));
    fixture.benefitAccount(other, "VA", 300_00);

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("livreCentavos").asLong()).isEqualTo(1_000_00);
    assertThat(body.get("proximoSalario").isNull()).isTrue();
    assertThat(body.get("beneficios")).isEmpty();
  }

  @Test
  void theRouteNeedsASession() throws Exception {
    assertThat(fixture.spendable(null).getStatus()).isEqualTo(401);
  }
}
