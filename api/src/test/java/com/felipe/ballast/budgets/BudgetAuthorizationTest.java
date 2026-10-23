package com.felipe.ballast.budgets;

import static com.felipe.ballast.budgets.BudgetsApi.error;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class BudgetAuthorizationTest {

  @Autowired MockMvc mvc;
  private BudgetsApi api;
  private BudgetsApi.Actor ana;
  private BudgetsApi.Actor bia;
  private String groceriesOfBia;

  @BeforeEach
  void setUp() throws Exception {
    api = new BudgetsApi(mvc);
    ana = api.newActor();
    bia = api.newActor();
    groceriesOfBia = api.categoryId(bia.session(), "Mercado");
    api.setOk(bia.session(), groceriesOfBia, 50_000, "2026-03");
  }

  private void assertNotFound(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(error(response)).isEqualTo("categoria não encontrada");
  }

  @Test
  void aUserDoesNotSeeTheBudgetsOfAnother() throws Exception {
    assertThat(api.listOk(ana.session(), "2026-03").get("orcamentos")).isEmpty();
  }

  @Test
  void aUserCannotSetOrRemoveTheBudgetOfAnotherCategory() throws Exception {
    assertNotFound(
        api.set(ana.session(), groceriesOfBia, Map.of("tetoCentavos", 1, "aPartirDe", "2026-03")));
    assertNotFound(api.remove(ana.session(), groceriesOfBia, "?aPartirDe=2026-03"));

    assertThat(
            api.listOk(bia.session(), "2026-03")
                .get("orcamentos")
                .get(0)
                .get("tetoCentavos")
                .asLong())
        .isEqualTo(50_000);
  }

  @Test
  void anUnknownOrMalformedCategoryIsNotFound() throws Exception {
    Map<String, Object> body = Map.of("tetoCentavos", 1, "aPartirDe", "2026-03");
    assertNotFound(api.set(ana.session(), BudgetsApi.randomId(), body));
    assertNotFound(api.set(ana.session(), "nao-e-uuid", body));
    assertNotFound(api.remove(ana.session(), "nao-e-uuid", "?aPartirDe=2026-03"));
  }

  @Test
  void everyRouteNeedsASession() throws Exception {
    assertThat(api.list(null, "?mes=2026-03").getStatus()).isEqualTo(401);
    assertThat(
            api.set(null, groceriesOfBia, Map.of("tetoCentavos", 1, "aPartirDe", "2026-03"))
                .getStatus())
        .isEqualTo(401);
    assertThat(api.remove(null, groceriesOfBia, "?aPartirDe=2026-03").getStatus()).isEqualTo(401);
  }
}
