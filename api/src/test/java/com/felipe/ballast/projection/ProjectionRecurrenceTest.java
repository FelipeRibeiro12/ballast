package com.felipe.ballast.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// A projeção lê o que a recorrência materializou (ADR-026, ADR-027). Hoje fixo em 2026-10-19 e o
// horizonte das ocorrências vai até 2027-10-19.
@IntegrationTest
class ProjectionRecurrenceTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private ProjectionFixture fixture;
  private ProjectionFixture.Actor actor;
  private String accountId;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
    fixture = new ProjectionFixture(mvc, jdbc);
    actor = fixture.newActor();
    accountId = fixture.account(actor, 0).toString();
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private String categoryId(String type) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(get("/categorias").cookie(actor.session())).andReturn().getResponse();
    for (JsonNode item : ProjectionFixture.body(response)) {
      if (item.get("tipo").asString().equals(type)) {
        return item.get("id").asString();
      }
    }
    throw new IllegalStateException("categoria de teste não encontrada: " + type);
  }

  private void createRecurrence(Map<String, Object> body) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/recorrencias")
                    .cookie(actor.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(ProjectionFixture.JSON.writeValueAsString(body)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as(ProjectionFixture.text(response)).isEqualTo(201);
  }

  private Map<String, Object> base(String type, long cents, Map<String, Object> rule)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tipo", type);
    body.put("titulo", "Recorrente");
    body.put("valorCentavos", cents);
    body.put("categoriaId", categoryId(type));
    body.put("contaId", accountId);
    body.put("regra", rule);
    return body;
  }

  private static JsonNode day(JsonNode body, String date) {
    for (JsonNode day : body.get("dias")) {
      if (day.get("data").asString().equals(date)) {
        return day;
      }
    }
    throw new IllegalStateException("dia fora da série: " + date);
  }

  @Test
  void aMonthlyRuleOnTheLastDaysFollowsShortMonthsAndFebruary() throws Exception {
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("frequencia", "mensal");
    rule.put("diaDoMes", 31);
    rule.put("inicio", "2026-10-01");
    createRecurrence(base("despesa", 1_000_00, rule));

    JsonNode body = fixture.projectionOk(actor.session(), "?dias=366");

    assertThat(day(body, "2026-10-30").get("saldoCentavos").asLong()).isZero();
    assertThat(day(body, "2026-10-31").get("saidasCentavos").asLong()).isEqualTo(1_000_00);
    assertThat(day(body, "2026-11-30").get("saldoCentavos").asLong()).isEqualTo(-2_000_00);
    assertThat(day(body, "2026-12-31").get("saldoCentavos").asLong()).isEqualTo(-3_000_00);
    assertThat(day(body, "2027-01-31").get("saldoCentavos").asLong()).isEqualTo(-4_000_00);
    // Fevereiro de 2027 não tem dia 31: a regra cai no 28.
    assertThat(day(body, "2027-02-27").get("saldoCentavos").asLong()).isEqualTo(-4_000_00);
    assertThat(day(body, "2027-02-28").get("saidasCentavos").asLong()).isEqualTo(1_000_00);
    assertThat(day(body, "2027-02-28").get("saldoCentavos").asLong()).isEqualTo(-5_000_00);
    assertThat(body.get("menorSaldo").get("data").asString()).isEqualTo("2027-09-30");
    assertThat(body.get("menorSaldo").get("saldoCentavos").asLong()).isEqualTo(-12_000_00);
    assertThat(body.get("fim").asString()).isEqualTo("2027-10-19");
  }

  @Test
  void aSalaryDividedInTwoPartsPutsEachPartOnItsDay() throws Exception {
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("frequencia", "mensal");
    rule.put("inicio", "2026-01-01");
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("diaDoMes", 5);
    first.put("percentualCentesimos", 4000);
    Map<String, Object> second = new LinkedHashMap<>();
    second.put("diaDoMes", 20);
    second.put("percentualCentesimos", 6000);
    Map<String, Object> division = new LinkedHashMap<>();
    division.put("modo", "percentual");
    division.put("parcelas", List.of(first, second));
    Map<String, Object> body = base("receita", 5_000_00, rule);
    body.put("divisao", division);
    createRecurrence(body);

    JsonNode projection = fixture.projectionOk(actor.session(), "?dias=60");

    // O dia 5 de outubro já passou e não vira ocorrência.
    assertThat(day(projection, "2026-10-19").get("saldoCentavos").asLong()).isZero();
    assertThat(day(projection, "2026-10-20").get("entradasCentavos").asLong()).isEqualTo(3_000_00);
    assertThat(day(projection, "2026-11-04").get("saldoCentavos").asLong()).isEqualTo(3_000_00);
    assertThat(day(projection, "2026-11-05").get("entradasCentavos").asLong()).isEqualTo(2_000_00);
    assertThat(day(projection, "2026-11-05").get("saldoCentavos").asLong()).isEqualTo(5_000_00);
    assertThat(day(projection, "2026-11-20").get("saldoCentavos").asLong()).isEqualTo(8_000_00);
  }
}
