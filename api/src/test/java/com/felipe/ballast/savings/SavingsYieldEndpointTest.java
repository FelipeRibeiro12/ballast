package com.felipe.ballast.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.nio.charset.StandardCharsets;
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
import tools.jackson.databind.json.JsonMapper;

// Hoje é quinta, 29/10/2026, ao meio-dia em São Paulo.
@IntegrationTest
class SavingsYieldEndpointTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private SavingsSupport support;
  private SavingsSupport.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    support = new SavingsSupport(mvc, jdbc);
    actor = support.newActor();
    support.clearCdi();
    support.publishCdi(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 10), "0.05");
    clock.freeze(Instant.parse("2026-10-29T15:00:00Z"));
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    support.clearCdi();
  }

  private MockHttpServletResponse run(SavingsSupport.Actor who, String accountId) throws Exception {
    var request = post("/contas/" + accountId + "/cofrinho/rendimento");
    if (who != null) {
      request.cookie(who.session());
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void launchesTodaysYieldAndReturnsCents() throws Exception {
    UUID pot = support.pot(actor, "100000.00", "100");

    MockHttpServletResponse response = run(actor, pot.toString());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(body(response).get("situacao").asString()).isEqualTo("lancado");
    assertThat(body(response).get("valor").asLong()).isEqualTo(50_00);
    assertThat(support.yields(pot)).hasSize(1);
  }

  @Test
  void aSecondCallSaysItWasAlreadyLaunchedAndDoesNotDuplicate() throws Exception {
    UUID pot = support.pot(actor, "100000.00", "100");
    run(actor, pot.toString());

    MockHttpServletResponse response = run(actor, pot.toString());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(body(response).get("situacao").asString()).isEqualTo("ja_lancado");
    assertThat(body(response).get("valor").isNull()).isTrue();
    assertThat(support.yields(pot)).hasSize(1);
  }

  @Test
  void withoutCdiItSaysSoAndWritesNothing() throws Exception {
    support.clearCdi();
    UUID pot = support.pot(actor, "100000.00", "100");

    MockHttpServletResponse response = run(actor, pot.toString());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(body(response).get("situacao").asString()).isEqualTo("sem_cdi");
    assertThat(support.markers(pot)).isZero();
  }

  @Test
  void withoutPercentItSaysSo() throws Exception {
    UUID pot = support.pot(actor, "100000.00", null);

    MockHttpServletResponse response = run(actor, pot.toString());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(body(response).get("situacao").asString()).isEqualTo("sem_percentual");
  }

  @Test
  void zeroBalanceAnswersNoYield() throws Exception {
    UUID pot = support.pot(actor, "0.00", "100");

    MockHttpServletResponse response = run(actor, pot.toString());

    assertThat(body(response).get("situacao").asString()).isEqualTo("sem_rendimento");
    assertThat(body(response).get("valor").isNull()).isTrue();
  }

  @Test
  void aNonBusinessDayAnswersNoYield() throws Exception {
    UUID pot = support.pot(actor, "100000.00", "100");
    clock.freeze(Instant.parse("2026-10-31T15:00:00Z"));

    MockHttpServletResponse response = run(actor, pot.toString());

    assertThat(body(response).get("situacao").asString()).isEqualTo("sem_rendimento");
    assertThat(support.markers(pot)).isZero();
  }

  @Test
  void anotherUsersAccountIs404WithTheSameBodyAsAMissingOne() throws Exception {
    SavingsSupport.Actor owner = support.newActor();
    UUID ownersPot = support.pot(owner, "100000.00", "100");

    MockHttpServletResponse foreign = run(actor, ownersPot.toString());
    MockHttpServletResponse missing = run(actor, UUID.randomUUID().toString());

    assertThat(foreign.getStatus()).isEqualTo(404);
    assertThat(missing.getStatus()).isEqualTo(404);
    assertThat(foreign.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(missing.getContentAsString(StandardCharsets.UTF_8));
    assertThat(support.markers(ownersPot)).isZero();
  }

  @Test
  void aNonSavingsAccountIs404() throws Exception {
    UUID checking = support.checking(actor, "100000.00");

    assertThat(run(actor, checking.toString()).getStatus()).isEqualTo(404);
  }

  @Test
  void anIdThatIsNotAUuidIs404() throws Exception {
    assertThat(run(actor, "abc").getStatus()).isEqualTo(404);
  }

  @Test
  void withoutASessionIs401() throws Exception {
    UUID pot = support.pot(actor, "100000.00", "100");

    assertThat(run(null, pot.toString()).getStatus()).isEqualTo(401);
    assertThat(support.markers(pot)).isZero();
  }
}
