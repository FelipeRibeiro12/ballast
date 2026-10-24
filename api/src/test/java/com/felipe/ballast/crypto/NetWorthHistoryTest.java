package com.felipe.ballast.crypto;

import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Hoje é 15 de março de 2026 em São Paulo.
@IntegrationTest
class NetWorthHistoryTest {

  // PTAX de 1,00 desde antes de todo movimento: reais e dólares ficam iguais neste teste.
  private static final LocalDate DOLLAR_DAY = LocalDate.of(2025, 1, 2);

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  @Autowired JdbcClient jdbc;
  private CryptoApi api;
  private CryptoApi.Actor actor;
  private String bitcoin;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2026-03-15T15:00:00Z"));
    jdbc.sql(
            "INSERT INTO indice (serie, data, valor) VALUES ('dolar', :d, 1) ON CONFLICT DO NOTHING")
        .param("d", DOLLAR_DAY)
        .update();
    api = new CryptoApi(mvc);
    actor = api.newActor();
    bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    jdbc.sql("DELETE FROM indice WHERE serie = 'dolar' AND data = :d")
        .param("d", DOLLAR_DAY)
        .update();
  }

  private String categoryId(CryptoApi.Actor owner, String name) throws Exception {
    for (JsonNode item : CryptoApi.body(api.send(owner.session(), get("/categorias"), null))) {
      if (item.get("nome").asString().equals(name)) {
        return item.get("id").asString();
      }
    }
    throw new IllegalStateException("categoria de teste não encontrada: " + name);
  }

  private void income(CryptoApi.Actor owner, long cents, String date) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tipo", "receita");
    body.put("titulo", "Salário");
    body.put("valorCentavos", cents);
    body.put("categoriaId", categoryId(owner, "Salário"));
    body.put("contaId", owner.checkingId());
    body.put("data", date);
    CryptoApi.expect(api.send(owner.session(), post("/lancamentos"), body), 201);
  }

  private MockHttpServletResponse monthly(CryptoApi.Actor owner, String query) throws Exception {
    return api.send(
        owner == null ? null : owner.session(), get("/patrimonio/mensal" + query), null);
  }

  private JsonNode monthlyOk(String query) throws Exception {
    return CryptoApi.expect(monthly(actor, query), 200);
  }

  private static List<Long> longs(JsonNode months, String field) {
    return months.findValues(field).stream().map(JsonNode::asLong).toList();
  }

  @Test
  void showsTheTwelveMonthsEndingTodayWithTheMonthEndValue() throws Exception {
    income(actor, 100_000, "2025-12-10");
    income(actor, 50_000, "2026-03-20");
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "2", "2026-02-01", 10_000));
    api.quoteOk(actor.session(), bitcoin, "100", "2026-02-10T12:00:00-03:00");

    JsonNode body = monthlyOk("");
    JsonNode months = body.get("meses");

    assertThat(body.get("mes").asString()).isEqualTo("2026-03");
    assertThat(months.findValuesAsString("mes"))
        .containsExactly(
            "2025-04", "2025-05", "2025-06", "2025-07", "2025-08", "2025-09", "2025-10", "2025-11",
            "2025-12", "2026-01", "2026-02", "2026-03");
    // Mês fechado vale no último dia; o corrente vale hoje, sem a receita do dia 20.
    assertThat(months.get(10).get("data").asString()).isEqualTo("2026-02-28");
    assertThat(months.get(11).get("data").asString()).isEqualTo("2026-03-15");
    assertThat(longs(months, "contasCentavos").subList(7, 12))
        .containsExactly(0L, 100_000L, 100_000L, 100_000L, 100_000L);
    assertThat(longs(months, "criptoCentavos").subList(9, 12))
        .containsExactly(0L, 20_000L, 20_000L);
    assertThat(longs(months, "totalCentavos").subList(9, 12))
        .containsExactly(100_000L, 120_000L, 120_000L);
    assertThat(longs(months, "rendaFixaCentavos")).allMatch(value -> value == 0);
  }

  @Test
  void aPositionWithoutQuoteInTheMonthIsCountedApartAndLeftOutOfTheTotal() throws Exception {
    income(actor, 1_000, "2026-01-05");
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2026-01-10", 5_000));
    api.quoteOk(actor.session(), bitcoin, "70", "2026-02-03T12:00:00-03:00");

    JsonNode months = monthlyOk("").get("meses");

    JsonNode january = months.get(9);
    assertThat(january.get("mes").asString()).isEqualTo("2026-01");
    assertThat(january.get("posicoesSemCotacao").asInt()).isEqualTo(1);
    assertThat(january.get("criptoCentavos").asLong()).isZero();
    assertThat(january.get("totalCentavos").asLong()).isEqualTo(1_000);
    JsonNode february = months.get(10);
    assertThat(february.get("posicoesSemCotacao").asInt()).isZero();
    assertThat(february.get("totalCentavos").asLong()).isEqualTo(8_000);
  }

  @Test
  void aPastMonthEndsTheSeriesOnItsLastDay() throws Exception {
    JsonNode months = monthlyOk("?mes=2025-12").get("meses");

    assertThat(months).hasSize(12);
    assertThat(months.get(0).get("mes").asString()).isEqualTo("2025-01");
    assertThat(months.get(11).get("data").asString()).isEqualTo("2025-12-31");
  }

  @Test
  void monthsAfterTheCurrentOneAreLeftOut() throws Exception {
    JsonNode body = monthlyOk("?mes=2026-05");

    assertThat(body.get("mes").asString()).isEqualTo("2026-05");
    assertThat(body.get("meses")).hasSize(10);
    assertThat(body.get("meses").get(9).get("mes").asString()).isEqualTo("2026-03");
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-13", "26-03", "2026-3", "1899-12", "2200-01", "2026-03-01", "x"})
  void rejectsAMonthOutOfFormat(String month) throws Exception {
    MockHttpServletResponse response = monthly(actor, "?mes=" + month);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.body(response).get("erro").asString())
        .isEqualTo("mes deve ser AAAA-MM com ano entre 1900 e 2199");
  }

  @Test
  void aUserDoesNotSeeTheNetWorthOfAnother() throws Exception {
    CryptoApi.Actor other = api.newActor();
    income(other, 900_000, "2026-01-05");

    JsonNode months = monthlyOk("").get("meses");

    assertThat(longs(months, "totalCentavos")).allMatch(value -> value == 0);
  }

  @Test
  void theRouteNeedsASession() throws Exception {
    assertThat(monthly(null, "").getStatus()).isEqualTo(401);
  }
}
