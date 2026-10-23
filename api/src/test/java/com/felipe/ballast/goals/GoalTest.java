package com.felipe.ballast.goals;

import static com.felipe.ballast.goals.GoalsApi.error;
import static com.felipe.ballast.goals.GoalsApi.goal;
import static com.felipe.ballast.goals.GoalsApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Hoje é 23 de outubro de 2026 em São Paulo.
@IntegrationTest
class GoalTest {

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  private GoalsApi api;
  private Cookie session;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2026-10-23T15:00:00Z"));
    api = new GoalsApi(mvc);
    session = api.newSession();
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  @Test
  void createsAGoalWithNothingSavedYet() throws Exception {
    JsonNode created =
        api.createOk(
            session,
            with(
                with(goal("  Viagem ao Chile ", 1_000_000), "dataAlvo", "2027-03-31"),
                "aporteMensalCentavos",
                150_000));

    assertThat(created.get("id").asString()).matches("[0-9a-f-]{36}");
    assertThat(created.get("titulo").asString()).isEqualTo("Viagem ao Chile");
    assertThat(created.get("valorAlvoCentavos").asLong()).isEqualTo(1_000_000);
    assertThat(created.get("dataAlvo").asString()).isEqualTo("2027-03-31");
    assertThat(created.get("aporteMensalCentavos").asLong()).isEqualTo(150_000);
    assertThat(created.get("acumuladoCentavos").asLong()).isZero();
    assertThat(created.get("faltaCentavos").asLong()).isEqualTo(1_000_000);
    assertThat(created.get("atingida").asBoolean()).isFalse();
    // Outubro a março são seis meses: 10.000 / 6 = 1.666,666... para cima.
    assertThat(created.get("necessarioPorMesCentavos").asLong()).isEqualTo(166_667);
    // 10.000 a 1.500 por mês: sete aportes, o sétimo em abril contando outubro.
    assertThat(created.get("chegadaPeloPlano").asString()).isEqualTo("2027-04");
    assertThat(created.get("chegadaPeloRitmo").isNull()).isTrue();
  }

  @Test
  void depositsAndWithdrawalsMoveWhatIsSavedAndThePace() throws Exception {
    String id = api.createOk(session, goal("Reserva", 600_000)).get("id").asString();
    api.depositOk(session, id, 100_000, "2026-07-15");
    api.depositOk(session, id, 100_000, "2026-08-15");
    api.depositOk(session, id, 150_000, "2026-09-15");
    JsonNode afterWithdrawal = api.depositOk(session, id, -50_000, "2026-09-20");
    // O mês atual fica fora do ritmo: ele ainda não fechou.
    JsonNode goal = api.depositOk(session, id, 20_000, "2026-10-01");

    assertThat(afterWithdrawal.get("acumuladoCentavos").asLong()).isEqualTo(300_000);
    assertThat(goal.get("acumuladoCentavos").asLong()).isEqualTo(320_000);
    assertThat(goal.get("faltaCentavos").asLong()).isEqualTo(280_000);
    // Julho a setembro: 300.000 em três meses, 100.000 por mês. Faltam 280.000: três meses.
    assertThat(goal.get("ritmoMensalCentavos").asLong()).isEqualTo(100_000);
    assertThat(goal.get("chegadaPeloRitmo").asString()).isEqualTo("2026-12");

    JsonNode history = GoalsApi.body(GoalsApi.expect(api.deposits(session, id), 200));
    assertThat(history).hasSize(5);
    assertThat(history.get(0).get("data").asString()).isEqualTo("2026-10-01");
    assertThat(history.get(0).get("valorCentavos").asLong()).isEqualTo(20_000);
  }

  @Test
  void reachingTheTargetEndsTheProjection() throws Exception {
    String id = api.createOk(session, goal("Celular", 100_000)).get("id").asString();

    JsonNode goal = api.depositOk(session, id, 120_000, "2026-10-01");

    assertThat(goal.get("atingida").asBoolean()).isTrue();
    assertThat(goal.get("faltaCentavos").asLong()).isZero();
    assertThat(goal.get("necessarioPorMesCentavos").isNull()).isTrue();
    assertThat(goal.get("chegadaPeloPlano").isNull()).isTrue();
  }

  @Test
  void aWithdrawalCannotTakeMoreThanWhatIsSaved() throws Exception {
    String id = api.createOk(session, goal("Reserva", 600_000)).get("id").asString();
    api.depositOk(session, id, 10_000, "2026-10-01");

    MockHttpServletResponse response = api.deposit(session, id, -10_001, "2026-10-02");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(error(response)).isEqualTo("retirada maior que o acumulado da meta");
  }

  @Test
  void removingADepositThatAWithdrawalDependsOnIsRefused() throws Exception {
    String id = api.createOk(session, goal("Reserva", 600_000)).get("id").asString();
    api.depositOk(session, id, 10_000, "2026-10-01");
    api.depositOk(session, id, -5_000, "2026-10-02");
    // Do mais novo ao mais antigo: o segundo é o depósito de 100,00.
    String depositId =
        GoalsApi.body(GoalsApi.expect(api.deposits(session, id), 200)).get(1).get("id").asString();

    MockHttpServletResponse response = api.removeDeposit(session, id, depositId);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(error(response)).isEqualTo("retirada maior que o acumulado da meta");
  }

  @Test
  void removesADeposit() throws Exception {
    String id = api.createOk(session, goal("Reserva", 600_000)).get("id").asString();
    api.depositOk(session, id, 10_000, "2026-10-01");
    String depositId =
        GoalsApi.body(GoalsApi.expect(api.deposits(session, id), 200)).get(0).get("id").asString();

    GoalsApi.expect(api.removeDeposit(session, id, depositId), 204);

    assertThat(api.listOk(session).get(0).get("acumuladoCentavos").asLong()).isZero();
  }

  @Test
  void aDepositCannotBeInTheFuture() throws Exception {
    String id = api.createOk(session, goal("Reserva", 600_000)).get("id").asString();

    MockHttpServletResponse response = api.deposit(session, id, 10_000, "2026-10-24");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(error(response)).isEqualTo("data do aporte não pode ser no futuro");
  }

  @Test
  void updatesAndDeletesAGoalKeepingItsDeposits() throws Exception {
    String id = api.createOk(session, goal("Reserva", 600_000)).get("id").asString();
    api.depositOk(session, id, 10_000, "2026-10-01");

    JsonNode updated =
        GoalsApi.body(
            GoalsApi.expect(
                api.update(
                    session, id, with(goal("Reserva de emergência", 900_000), "dataAlvo", null)),
                200));

    assertThat(updated.get("titulo").asString()).isEqualTo("Reserva de emergência");
    assertThat(updated.get("acumuladoCentavos").asLong()).isEqualTo(10_000);
    GoalsApi.expect(api.remove(session, id), 204);
    assertThat(api.listOk(session)).isEmpty();
  }

  @Test
  void listsByTitle() throws Exception {
    api.createOk(session, goal("viagem", 1));
    api.createOk(session, goal("Carro", 1));

    assertThat(api.listOk(session).findValuesAsString("titulo")).containsExactly("Carro", "viagem");
  }

  @Test
  void rejectsInvalidInput() throws Exception {
    assertThat(error(GoalsApi.expect(api.create(session, goal("", 1)), 400)))
        .isEqualTo(
            "titulo deve ter entre 1 e 100 caracteres e não pode ter caracteres de controle");
    assertThat(error(GoalsApi.expect(api.create(session, goal("Viagem", 0)), 400)))
        .isEqualTo("valorAlvoCentavos deve ser um inteiro entre 1 e 9007199254740991");
    assertThat(
            error(
                GoalsApi.expect(
                    api.create(session, with(goal("Viagem", 1), "dataAlvo", "2026-02-30")), 400)))
        .isEqualTo("dataAlvo deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199");
    assertThat(
            error(
                GoalsApi.expect(
                    api.create(session, with(goal("Viagem", 1), "aporteMensalCentavos", 0)), 400)))
        .isEqualTo("aporteMensalCentavos deve ser um inteiro entre 1 e 9007199254740991");
    assertThat(GoalsApi.expect(api.create(session, with(goal("Viagem", 1), "extra", 1)), 400))
        .isNotNull();
    String id = api.createOk(session, goal("Viagem", 1)).get("id").asString();
    assertThat(error(GoalsApi.expect(api.deposit(session, id, 0, "2026-10-01"), 400)))
        .isEqualTo(
            "valorCentavos deve ser um inteiro diferente de zero, de -9007199254740991 a 9007199254740991");
  }
}
