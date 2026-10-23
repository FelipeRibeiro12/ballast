package com.felipe.ballast.goals;

import static com.felipe.ballast.goals.GoalsApi.error;
import static com.felipe.ballast.goals.GoalsApi.goal;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class GoalAuthorizationTest {

  @Autowired MockMvc mvc;
  private GoalsApi api;
  private Cookie ana;
  private Cookie bia;
  private String goalOfBia;
  private String depositOfBia;

  @BeforeEach
  void setUp() throws Exception {
    api = new GoalsApi(mvc);
    ana = api.newSession();
    bia = api.newSession();
    goalOfBia = api.createOk(bia, goal("Viagem da Bia", 100_000)).get("id").asString();
    api.depositOk(bia, goalOfBia, 10_000, "2026-01-01");
    depositOfBia =
        GoalsApi.body(GoalsApi.expect(api.deposits(bia, goalOfBia), 200))
            .get(0)
            .get("id")
            .asString();
  }

  private void assertNotFound(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(error(response)).isEqualTo("meta não encontrada");
  }

  @Test
  void aUserDoesNotSeeNorTouchTheGoalOfAnother() throws Exception {
    assertThat(api.listOk(ana)).isEmpty();
    assertNotFound(api.update(ana, goalOfBia, goal("Minha", 1)));
    assertNotFound(api.remove(ana, goalOfBia));
    assertNotFound(api.deposits(ana, goalOfBia));
    assertNotFound(api.deposit(ana, goalOfBia, 1, "2026-01-01"));
    assertNotFound(api.removeDeposit(ana, goalOfBia, depositOfBia));

    assertThat(api.listOk(bia).get(0).get("acumuladoCentavos").asLong()).isEqualTo(10_000);
  }

  @Test
  void aDepositOfAnotherGoalIsNotFoundUnderMyGoal() throws Exception {
    String goalOfAna = api.createOk(ana, goal("Minha", 1_000)).get("id").asString();

    assertNotFound(api.removeDeposit(ana, goalOfAna, depositOfBia));
  }

  @Test
  void unknownAndMalformedIdsAreNotFound() throws Exception {
    assertNotFound(api.remove(ana, UUID.randomUUID().toString()));
    assertNotFound(api.remove(ana, "nao-e-uuid"));
    assertNotFound(api.deposits(ana, "nao-e-uuid"));
  }

  @Test
  void everyRouteNeedsASession() throws Exception {
    assertThat(api.list(null).getStatus()).isEqualTo(401);
    assertThat(api.create(null, goal("x", 1)).getStatus()).isEqualTo(401);
    assertThat(api.deposit(null, goalOfBia, 1, "2026-01-01").getStatus()).isEqualTo(401);
    assertThat(api.remove(null, goalOfBia).getStatus()).isEqualTo(401);
  }
}
