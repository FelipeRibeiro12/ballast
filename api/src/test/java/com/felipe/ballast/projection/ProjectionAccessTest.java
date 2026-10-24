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

// Autorização de GET /saldo-projetado: o escopo é sempre o usuário da sessão.
@IntegrationTest
class ProjectionAccessTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);
  private static final String NOT_FOUND = "{\"erro\":\"conta não encontrada\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private ProjectionFixture fixture;
  private ProjectionFixture.Actor alice;
  private ProjectionFixture.Actor bruno;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
    fixture = new ProjectionFixture(mvc, jdbc);
    alice = fixture.newActor();
    bruno = fixture.newActor();
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  @Test
  void withoutASessionTheAnswerIs401() throws Exception {
    assertThat(fixture.projection(null, "").getStatus()).isEqualTo(401);
    assertThat(fixture.projection(null, "?dias=abc").getStatus()).isEqualTo(401);
  }

  @Test
  void anotherUsersAccountAnswersLikeAnUnknownOne() throws Exception {
    UUID brunoAccount = fixture.account(bruno, 500_00);

    MockHttpServletResponse foreign =
        fixture.projection(alice.session(), "?contaId=" + brunoAccount);
    MockHttpServletResponse unknown =
        fixture.projection(alice.session(), "?contaId=" + UUID.randomUUID());

    assertThat(foreign.getStatus()).isEqualTo(404);
    assertThat(unknown.getStatus()).isEqualTo(404);
    assertThat(ProjectionFixture.text(foreign)).isEqualTo(NOT_FOUND);
    assertThat(ProjectionFixture.text(unknown)).isEqualTo(NOT_FOUND);
  }

  @Test
  void aCryptoAccountHasNoBalanceToProjectAndTheFilterIsRefused() throws Exception {
    UUID crypto = fixture.cryptoAccount(alice);

    MockHttpServletResponse response = fixture.projection(alice.session(), "?contaId=" + crypto);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(ProjectionFixture.text(response))
        .isEqualTo("{\"erro\":\"conta cripto não tem saldo projetado\"}");
    assertThat(fixture.projection(alice.session(), "").getStatus()).isEqualTo(200);
  }

  @Test
  void anotherUsersCryptoAccountStillAnswers404() throws Exception {
    UUID brunoCrypto = fixture.cryptoAccount(bruno);

    MockHttpServletResponse response =
        fixture.projection(alice.session(), "?contaId=" + brunoCrypto);

    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void anArchivedAccountOfTheUserIsValid() throws Exception {
    UUID archived = fixture.account(alice, 10_00, true);

    MockHttpServletResponse response = fixture.projection(alice.session(), "?contaId=" + archived);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void theUppercaseFormOfTheOwnAccountIdIsAccepted() throws Exception {
    UUID own = fixture.account(alice, 10_00);

    MockHttpServletResponse response =
        fixture.projection(alice.session(), "?contaId=" + own.toString().toUpperCase());

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void anotherUsersMovementNeverReachesTheTotal() throws Exception {
    UUID aliceAccount = fixture.account(alice, 100_00);
    UUID brunoAccount = fixture.account(bruno, 900_00);
    fixture.income(bruno, brunoAccount, "realizado", 50_00, TODAY);
    fixture.expense(bruno, brunoAccount, "previsto", 30_00, TODAY.minusDays(2));
    fixture.income(bruno, brunoAccount, "previsto", 20_00, TODAY.plusDays(1));
    fixture.income(alice, aliceAccount, "previsto", 5_00, TODAY.plusDays(1));

    JsonNode body = fixture.projectionOk(alice.session(), "?dias=3");

    assertThat(body.get("saldoAtualCentavos").asLong()).isEqualTo(100_00);
    assertThat(body.get("vencidasCentavos").asLong()).isZero();
    assertThat(body.get("dias").get(0).get("saldoCentavos").asLong()).isEqualTo(100_00);
    assertThat(body.get("dias").get(0).get("entradasCentavos").asLong()).isZero();
    assertThat(body.get("dias").get(1).get("saldoCentavos").asLong()).isEqualTo(105_00);
    assertThat(body.get("dias").get(1).get("entradasCentavos").asLong()).isEqualTo(5_00);
  }
}
