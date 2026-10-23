package com.felipe.ballast.crypto;

import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.FakeIndexSource;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.fixedincome.IndexPoint;
import com.felipe.ballast.fixedincome.IndexSeries;
import java.math.BigDecimal;
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

// A PTAX é global e o banco é compartilhado: este teste vive em março de 2036 (ADR-038).
@IntegrationTest
class DollarConversionTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  @Autowired FakeIndexSource source;
  private CryptoApi api;
  private CryptoApi.Actor actor;
  private String bitcoin;

  @BeforeEach
  void setUp() throws Exception {
    clearDollar();
    dollar("2036-03-02", "5.0383");
    dollar("2036-03-10", "5.2");
    clock.freeze(Instant.parse("2036-03-20T15:00:00Z"));
    api = new CryptoApi(mvc);
    actor = api.newActor();
    bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    source.reset();
    clearDollar();
  }

  private void clearDollar() {
    jdbc.sql("DELETE FROM indice WHERE serie = 'dolar' AND data >= DATE '2036-01-01'").update();
  }

  private void dollar(String date, String value) {
    jdbc.sql("INSERT INTO indice (serie, data, valor) VALUES ('dolar', :d, CAST(:v AS numeric))")
        .param("d", LocalDate.parse(date))
        .param("v", value)
        .update();
  }

  @Test
  void aBuyInDollarsShowsTheCostInReaisByThePtaxOfItsDate() throws Exception {
    JsonNode created =
        CryptoApi.expect(
            api.move(
                actor.session(),
                movement(actor.cryptoId(), bitcoin, "compra", "0.5", "2036-03-03", 3_000_000)),
            201);

    assertThat(created.get("custoUsdCentavos").asLong()).isEqualTo(3_000_000);
    assertThat(created.get("custoCentavos").asLong()).isEqualTo(15_114_900);
  }

  @Test
  void aPositionShowsTokensDollarsAndReaisByThePtaxOfTheDate() throws Exception {
    api.moveOk(
        actor.session(),
        movement(actor.cryptoId(), bitcoin, "compra", "0.5", "2036-03-03", 3_000_000));
    api.quoteOk(actor.session(), bitcoin, "70000.5", "2036-03-10T12:00:00-03:00");

    JsonNode body = CryptoApi.expect(api.positions(actor.session(), "?data=2036-03-10"), 200);

    JsonNode position = body.get("posicoes").get(0);
    assertThat(body.get("dolar").get("valor").asString()).isEqualTo("5.2");
    assertThat(body.get("dolar").get("data").asString()).isEqualTo("2036-03-10");
    assertThat(position.get("quantidade").asString()).isEqualTo("0.5");
    assertThat(position.get("cotacao").get("precoUsd").asString()).isEqualTo("70000.5");
    assertThat(position.get("valorUsdCentavos").asLong()).isEqualTo(3_500_025);
    assertThat(position.get("valorCentavos").asLong()).isEqualTo(18_200_130);
    assertThat(position.get("custoUsdCentavos").asLong()).isEqualTo(3_000_000);
    assertThat(position.get("custoCentavos").asLong()).isEqualTo(15_114_900);
    JsonNode netWorth = CryptoApi.expect(api.netWorth(actor.session(), "?data=2036-03-10"), 200);
    assertThat(netWorth.get("criptoCentavos").asLong()).isEqualTo(18_200_130);
  }

  @Test
  void withoutPtaxTheDollarValueStaysAndTheReaisValueIsLeftOut() throws Exception {
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2036-03-01", 100_000));
    api.quoteOk(actor.session(), bitcoin, "2000", "2036-03-01T12:00:00Z");

    JsonNode body = CryptoApi.expect(api.positions(actor.session(), "?data=2036-03-01"), 200);
    JsonNode netWorth = CryptoApi.expect(api.netWorth(actor.session(), "?data=2036-03-01"), 200);

    JsonNode position = body.get("posicoes").get(0);
    assertThat(body.get("dolar").isNull()).isTrue();
    assertThat(position.get("valorUsdCentavos").asLong()).isEqualTo(200_000);
    assertThat(position.get("valorCentavos").isNull()).isTrue();
    assertThat(position.get("custoCentavos").isNull()).isTrue();
    assertThat(netWorth.get("criptoCentavos").asLong()).isZero();
    assertThat(netWorth.get("semCotacao")).hasSize(1);
  }

  @Test
  void anOldMovementInReaisKeepsItsValueAndGainsTheDollarOne() throws Exception {
    jdbc.sql(
            "INSERT INTO movimento_cripto (id, usuario_id, conta_id, ativo_id, tipo, quantidade,"
                + " data, custo, moeda) VALUES (:id, :u, CAST(:c AS uuid), CAST(:a AS uuid),"
                + " 'compra', 1, DATE '2036-03-03', 1000.00, 'BRL')")
        .param("id", UUID.randomUUID())
        .param("u", actor.user().id())
        .param("c", actor.cryptoId())
        .param("a", bitcoin)
        .update();

    JsonNode listed = CryptoApi.body(api.listMovements(actor.session(), "")).get(0);

    assertThat(listed.get("custoCentavos").asLong()).isEqualTo(100_000);
    assertThat(listed.get("custoUsdCentavos").asLong()).isEqualTo(19_848);
  }

  @Test
  void aMovementBeforeTheFirstPtaxFetchesItAndAnswersWithTheReais() throws Exception {
    source.publish(
        IndexSeries.DOLLAR, new IndexPoint(LocalDate.of(2036, 2, 27), new BigDecimal("5")));

    JsonNode created =
        CryptoApi.expect(
            api.move(
                actor.session(),
                movement(actor.cryptoId(), bitcoin, "compra", "1", "2036-02-27", 100_000)),
            201);

    assertThat(created.get("custoCentavos").asLong()).isEqualTo(500_000);
  }

  @Test
  void anOldQuoteInReaisKeepsTheReaisValueWithoutPtaxAndLosesOnlyTheDollarOne() throws Exception {
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2036-03-01", 100_000));
    jdbc.sql(
            "INSERT INTO cotacao (usuario_id, ativo_id, momento, preco, fonte, moeda) VALUES"
                + " (:u, CAST(:a AS uuid), TIMESTAMPTZ '2036-03-01 12:00:00Z', 2500, 'manual',"
                + " 'BRL')")
        .param("u", actor.user().id())
        .param("a", bitcoin)
        .update();

    JsonNode position =
        CryptoApi.expect(api.positions(actor.session(), "?data=2036-03-01"), 200)
            .get("posicoes")
            .get(0);
    JsonNode netWorth = CryptoApi.expect(api.netWorth(actor.session(), "?data=2036-03-01"), 200);

    assertThat(position.get("valorCentavos").asLong()).isEqualTo(250_000);
    assertThat(position.get("valorUsdCentavos").isNull()).isTrue();
    assertThat(position.get("cotacao").get("precoUsd").isNull()).isTrue();
    assertThat(netWorth.get("criptoCentavos").asLong()).isEqualTo(250_000);
    assertThat(netWorth.get("semCotacao")).isEmpty();
  }

  @Test
  void theDollarPriceOfAnOldQuoteInReaisHasAtMostEightPlaces() throws Exception {
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2036-03-03", 100_000));
    jdbc.sql(
            "INSERT INTO cotacao (usuario_id, ativo_id, momento, preco, fonte, moeda) VALUES"
                + " (:u, CAST(:a AS uuid), TIMESTAMPTZ '2036-03-03 12:00:00Z', 1000, 'manual',"
                + " 'BRL')")
        .param("u", actor.user().id())
        .param("a", bitcoin)
        .update();

    JsonNode quote =
        CryptoApi.expect(api.positions(actor.session(), "?data=2036-03-03"), 200)
            .get("posicoes")
            .get(0)
            .get("cotacao");

    // 1000 / 5,0383 = 198,47964591...
    assertThat(quote.get("precoUsd").asString()).isEqualTo("198.47964591");
  }
}
