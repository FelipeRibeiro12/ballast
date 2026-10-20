package com.felipe.ballast.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Validação estrita da query de GET /saldo-projetado: o que a rota não entende vira 400 com
// mensagem fixa, nunca um valor adivinhado.
@IntegrationTest
class ProjectionParametersTest {

  private static final String INVALID_DAYS = "dias deve ser um inteiro de 1 a 366";
  private static final String INVALID_ACCOUNT = "contaId deve ser um UUID";

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

  @ParameterizedTest
  @ValueSource(strings = {"1", "90", "366"})
  void acceptsTheEdgesOfTheRange(String days) throws Exception {
    MockHttpServletResponse response = fixture.projection(actor.session(), "?dias=" + days);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(ProjectionFixture.body(response).get("dias")).hasSize(Integer.parseInt(days));
  }

  @Test
  void theLastPointOf366DaysIsTodayPlus365() throws Exception {
    var body = fixture.projectionOk(actor.session(), "?dias=366");

    assertThat(body.get("fim").asString()).isEqualTo("2027-10-19");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "?dias=0",
        "?dias=367",
        "?dias=-1",
        "?dias=abc",
        "?dias=",
        "?dias=%20",
        "?dias=1.5",
        "?dias=1e2",
        "?dias=%2B5",
        "?dias=0x10",
        "?dias=090a",
        "?dias=99999999999999999999",
        "?dias=%D9%A5",
        "?dias=5&dias=5",
        "?dias=5&dias=6",
        "?dias=5&dias=",
      })
  void refusesADaysValueThatIsNotAnIntegerFrom1To366(String query) throws Exception {
    MockHttpServletResponse response = fixture.projection(actor.session(), query);

    assertThat(response.getStatus()).as(query).isEqualTo(400);
    assertThat(ProjectionFixture.text(response)).isEqualTo(ProjectionFixture.error(INVALID_DAYS));
  }

  @Test
  void leadingZerosAreNotAnInteger() throws Exception {
    MockHttpServletResponse response = fixture.projection(actor.session(), "?dias=090");

    assertThat(response.getStatus()).isEqualTo(400);
  }

  static Stream<String> malformedAccountIds() {
    return Stream.of(
        "?contaId=abc",
        "?contaId=",
        "?contaId=1-1-1-1-1",
        "?contaId=%7B00000000-0000-0000-0000-000000000000%7D",
        "?contaId=00000000-0000-0000-0000-00000000000g",
        "?contaId=00000000-0000-0000-0000-000000000000&contaId=00000000-0000-0000-0000-000000000000");
  }

  @ParameterizedTest
  @MethodSource("malformedAccountIds")
  void refusesAMalformedOrRepeatedAccountId(String query) throws Exception {
    MockHttpServletResponse response = fixture.projection(actor.session(), query);

    assertThat(response.getStatus()).as(query).isEqualTo(400);
    assertThat(ProjectionFixture.text(response))
        .isEqualTo(ProjectionFixture.error(INVALID_ACCOUNT));
  }

  @Test
  void anUnknownParameterIsIgnoredLikeInTheOtherListings() throws Exception {
    MockHttpServletResponse response = fixture.projection(actor.session(), "?dias=2&x=1");

    assertThat(response.getStatus()).isEqualTo(200);
  }
}
