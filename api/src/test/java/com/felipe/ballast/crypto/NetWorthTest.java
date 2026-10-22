package com.felipe.ballast.crypto;

import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class NetWorthTest {

  private static final String INVALID_DATE =
      "{\"erro\":\"data deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199\"}";

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  private CryptoApi api;
  private CryptoApi.Actor actor;
  private String bitcoin;
  private String expense;
  private String income;

  @BeforeEach
  void setUp() throws Exception {
    api = new CryptoApi(mvc);
    actor = api.newActor();
    bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
    expense = categoryId("Mercado");
    income = categoryId("Salário");
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private String categoryId(String name) throws Exception {
    for (JsonNode item : CryptoApi.body(api.send(actor.session(), get("/categorias"), null))) {
      if (item.get("nome").asString().equals(name)) {
        return item.get("id").asString();
      }
    }
    throw new IllegalStateException("categoria de teste não encontrada: " + name);
  }

  private void entry(String accountId, String type, long cents, String date) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tipo", type);
    body.put("titulo", "Teste");
    body.put("valorCentavos", cents);
    body.put("categoriaId", type.equals("receita") ? income : expense);
    body.put("contaId", accountId);
    body.put("data", date);
    CryptoApi.expect(api.send(actor.session(), post("/lancamentos"), body), 201);
  }

  private void buy(String quantity, String date, long cents) throws Exception {
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", quantity, date, cents));
  }

  private void sell(String quantity, String date, long cents) throws Exception {
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "venda", quantity, date, cents));
  }

  private JsonNode positions(String query) throws Exception {
    return CryptoApi.expect(api.positions(actor.session(), query), 200);
  }

  private JsonNode netWorth(String query) throws Exception {
    return CryptoApi.expect(api.netWorth(actor.session(), query), 200);
  }

  @Test
  void aPositionIsTheSumOfTheMovementsUpToTheDateWithCostBasis() throws Exception {
    buy("1", "2026-03-01", 30_000_000);
    buy("0.5", "2026-03-10", 20_000_000);
    sell("0.25", "2026-03-20", 12_000_000);

    JsonNode before = positions("?data=2026-03-09").get("posicoes");
    JsonNode after = positions("?data=2026-03-20").get("posicoes");

    assertThat(before).hasSize(1);
    assertThat(before.get(0).get("quantidade").asString()).isEqualTo("1");
    assertThat(before.get(0).get("custoCentavos").asLong()).isEqualTo(30_000_000);
    assertThat(after.get(0).get("contaId").asString()).isEqualTo(actor.cryptoId());
    assertThat(after.get(0).get("ativoId").asString()).isEqualTo(bitcoin);
    assertThat(after.get(0).get("quantidade").asString()).isEqualTo("1.25");
    assertThat(after.get(0).get("custoCentavos").asLong()).isEqualTo(38_000_000);
    assertThat(after.get(0).get("cotacao").isNull()).isTrue();
    assertThat(after.get(0).get("valorCentavos").isNull()).isTrue();
    assertThat(positions("?data=2026-02-28").get("posicoes")).isEmpty();
  }

  @Test
  void aSoldOutPositionDisappearsAndItsCostCanBeNegative() throws Exception {
    buy("1", "2026-03-01", 10_000);
    sell("0.5", "2026-03-02", 30_000);

    JsonNode half = positions("?data=2026-03-02").get("posicoes").get(0);
    assertThat(half.get("custoCentavos").asLong()).isEqualTo(-20_000);

    sell("0.5", "2026-03-03", 5_000);
    assertThat(positions("?data=2026-03-03").get("posicoes")).isEmpty();
  }

  @Test
  void theValueUsesTheLastQuoteUpToTheEndOfTheDayInSaoPaulo() throws Exception {
    buy("2", "2026-03-01", 100);
    api.quoteOk(actor.session(), bitcoin, "100", "2026-03-10T12:00:00-03:00");
    // 23:30 em São Paulo ainda é dia 10, mesmo já sendo dia 11 em UTC.
    api.quoteOk(actor.session(), bitcoin, "150", "2026-03-10T23:30:00-03:00");
    api.quoteOk(actor.session(), bitcoin, "999", "2026-03-11T00:00:00-03:00");

    JsonNode position = positions("?data=2026-03-10").get("posicoes").get(0);

    assertThat(position.get("cotacao").get("preco").asString()).isEqualTo("150");
    assertThat(Instant.parse(position.get("cotacao").get("momento").asString()))
        .isEqualTo(Instant.parse("2026-03-11T02:30:00Z"));
    assertThat(position.get("valorCentavos").asLong()).isEqualTo(30_000);
    assertThat(positions("?data=2026-03-09").get("posicoes").get(0).get("cotacao").isNull())
        .isTrue();
  }

  @Test
  void theValueIsRoundedOnceHalfEven() throws Exception {
    // 0,00000001 x 50 = 0,0000005 real: abaixo de meio centavo, vai a zero.
    // 0,5 centavo exato arredonda para o par (0); 1,5 centavo arredonda para 2.
    buy("0.00000001", "2026-03-01", 1);
    api.quoteOk(actor.session(), bitcoin, "50", "2026-03-01T12:00:00Z");
    assertThat(positions("?data=2026-03-01").get("posicoes").get(0).get("valorCentavos").asLong())
        .isZero();

    api.quoteOk(actor.session(), bitcoin, "500000", "2026-03-02T12:00:00Z");
    assertThat(positions("?data=2026-03-02").get("posicoes").get(0).get("valorCentavos").asLong())
        .isZero();

    api.quoteOk(actor.session(), bitcoin, "1500000", "2026-03-03T12:00:00Z");
    assertThat(positions("?data=2026-03-03").get("posicoes").get(0).get("valorCentavos").asLong())
        .isEqualTo(2);
  }

  @Test
  void theNetWorthAddsAccountsUpToTheDateAndQuotedCrypto() throws Exception {
    entry(actor.checkingId(), "receita", 500_000, "2026-03-01");
    entry(actor.checkingId(), "despesa", 100_000, "2026-03-15");
    buy("2", "2026-03-01", 100);
    api.quoteOk(actor.session(), bitcoin, "1000.005", "2026-03-01T12:00:00Z");

    JsonNode early = netWorth("?data=2026-03-10");
    JsonNode later = netWorth("?data=2026-03-15");

    assertThat(early.get("data").asString()).isEqualTo("2026-03-10");
    assertThat(early.get("contasCentavos").asLong()).isEqualTo(500_000);
    assertThat(early.get("criptoCentavos").asLong()).isEqualTo(200_001);
    assertThat(early.get("totalCentavos").asLong()).isEqualTo(700_001);
    assertThat(early.get("semCotacao")).isEmpty();
    assertThat(later.get("contasCentavos").asLong()).isEqualTo(400_000);
    assertThat(later.get("totalCentavos").asLong()).isEqualTo(600_001);
  }

  @Test
  void anUnquotedPositionIsListedApartAndLeftOutOfTheTotal() throws Exception {
    entry(actor.checkingId(), "receita", 1_000, "2026-03-01");
    buy("0.3", "2026-03-01", 100);

    JsonNode body = netWorth("?data=2026-03-01");

    assertThat(body.get("criptoCentavos").asLong()).isZero();
    assertThat(body.get("totalCentavos").asLong()).isEqualTo(1_000);
    assertThat(body.get("semCotacao")).hasSize(1);
    JsonNode unquoted = body.get("semCotacao").get(0);
    assertThat(unquoted.get("contaId").asString()).isEqualTo(actor.cryptoId());
    assertThat(unquoted.get("ativoId").asString()).isEqualTo(bitcoin);
    assertThat(unquoted.get("quantidade").asString()).isEqualTo("0.3");
  }

  @Test
  void archivedAccountsAndOtherUsersStayOut() throws Exception {
    entry(actor.checkingId(), "receita", 1_000, "2026-03-01");
    buy("1", "2026-03-01", 100);
    api.quoteOk(actor.session(), bitcoin, "10", "2026-03-01T12:00:00Z");
    CryptoApi.Actor other = api.newActor();
    String theirs = api.createAssetOk(other.session(), CryptoApi.bitcoin());
    api.moveOk(
        other.session(), movement(other.cryptoId(), theirs, "compra", "5", "2026-03-01", 100));

    api.send(actor.session(), patch("/contas/" + actor.cryptoId()), Map.of("arquivada", true));
    api.send(actor.session(), patch("/contas/" + actor.checkingId()), Map.of("arquivada", true));
    JsonNode body = netWorth("?data=2026-03-01");

    assertThat(body.get("totalCentavos").asLong()).isZero();
    assertThat(body.get("semCotacao")).isEmpty();
    assertThat(positions("?data=2026-03-01").get("posicoes")).isEmpty();
  }

  @Test
  void withoutADateUsesTodayInSaoPaulo() throws Exception {
    // 01:00 UTC do dia 11 ainda é dia 10 em São Paulo.
    clock.freeze(Instant.parse("2026-03-11T01:00:00Z"));
    buy("1", "2026-03-10", 100);

    assertThat(netWorth("").get("data").asString()).isEqualTo("2026-03-10");
    assertThat(positions("").get("data").asString()).isEqualTo("2026-03-10");
    assertThat(positions("").get("posicoes")).hasSize(1);
  }

  @Test
  void refusesAMalformedDate() throws Exception {
    for (String date : List.of("2026-3-1", "2026-02-30", "1899-12-31", "2200-01-01", "hoje")) {
      MockHttpServletResponse netWorth = api.netWorth(actor.session(), "?data=" + date);
      MockHttpServletResponse positions = api.positions(actor.session(), "?data=" + date);

      assertThat(netWorth.getStatus()).as(date).isEqualTo(400);
      assertThat(CryptoApi.text(netWorth)).isEqualTo(INVALID_DATE);
      assertThat(CryptoApi.text(positions)).isEqualTo(INVALID_DATE);
    }
  }

  @Test
  void aValueBeyondTheSafeIntegerIs400() throws Exception {
    buy("99999999999999999999", "2026-03-01", 100);
    api.quoteOk(actor.session(), bitcoin, "999999999999", "2026-03-01T12:00:00Z");

    MockHttpServletResponse netWorth = api.netWorth(actor.session(), "?data=2026-03-01");
    MockHttpServletResponse positions = api.positions(actor.session(), "?data=2026-03-01");

    assertThat(netWorth.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(netWorth)).isEqualTo("{\"erro\":\"patrimônio fora do limite\"}");
    assertThat(CryptoApi.text(positions)).isEqualTo("{\"erro\":\"patrimônio fora do limite\"}");
  }

  @Test
  void ordersPositionsByAccountNameThenSymbol() throws Exception {
    String first = api.createAccountOk(actor.session(), "A exchange", "cripto");
    Map<String, Object> eth = CryptoApi.bitcoin();
    eth.put("rede", "ethereum");
    eth.put("simbolo", "ETH");
    eth.put("decimais", 18);
    String ether = api.createAssetOk(actor.session(), eth);
    buy("1", "2026-03-01", 100);
    api.moveOk(actor.session(), movement(first, ether, "compra", "1", "2026-03-01", 100));
    api.moveOk(actor.session(), movement(first, bitcoin, "compra", "1", "2026-03-01", 100));

    JsonNode list = positions("?data=2026-03-01").get("posicoes");

    assertThat(list)
        .extracting(p -> p.get("contaId").asString() + p.get("ativoId").asString())
        .containsExactly(first + bitcoin, first + ether, actor.cryptoId() + bitcoin);
  }

  @Test
  void withoutSessionIs401() throws Exception {
    assertThat(api.netWorth(null, "").getStatus()).isEqualTo(401);
    assertThat(api.positions(null, "").getStatus()).isEqualTo(401);
  }
}
