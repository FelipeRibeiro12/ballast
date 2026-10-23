package com.felipe.ballast.crypto;

import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// A PTAX é global e o banco é compartilhado: este teste vive em junho de 2037, com o dólar a 5 no
// começo do mês e a 5,5 depois do dia 10 (ADR-042).
@IntegrationTest
class CryptoGainTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private CryptoApi api;
  private CryptoApi.Actor actor;
  private String bitcoin;

  @BeforeEach
  void setUp() throws Exception {
    clearDollar();
    dollar("2037-06-01", "5");
    dollar("2037-06-10", "5.5");
    clock.freeze(Instant.parse("2037-06-20T15:00:00Z"));
    api = new CryptoApi(mvc);
    actor = api.newActor();
    bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    clearDollar();
  }

  private void clearDollar() {
    jdbc.sql(
            "DELETE FROM indice WHERE serie = 'dolar'"
                + " AND data BETWEEN DATE '2037-01-01' AND DATE '2037-12-31'")
        .update();
  }

  private void dollar(String date, String value) {
    jdbc.sql("INSERT INTO indice (serie, data, valor) VALUES ('dolar', :d, CAST(:v AS numeric))")
        .param("d", LocalDate.parse(date))
        .param("v", value)
        .update();
  }

  private void move(String type, String quantity, String date, long usdCents) throws Exception {
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, type, quantity, date, usdCents));
  }

  private JsonNode onlyPosition() throws Exception {
    JsonNode body = CryptoApi.expect(api.positions(actor.session(), "?data=2037-06-20"), 200);
    assertThat(body.get("posicoes")).hasSize(1);
    return body.get("posicoes").get(0);
  }

  @Test
  void aPositionSplitsWhatWasPaidFromWhatThePriceDid() throws Exception {
    move("compra", "2", "2037-06-02", 10_000);
    move("compra", "2", "2037-06-03", 30_000);
    move("venda", "1", "2037-06-05", 15_000);
    api.quoteOk(actor.session(), bitcoin, "120", "2037-06-15T12:00:00Z");

    JsonNode position = onlyPosition();

    assertThat(position.get("quantidade").asString()).isEqualTo("3");
    assertThat(position.get("valorUsdCentavos").asLong()).isEqualTo(36_000);
    assertThat(position.get("custoUsdCentavos").asLong()).isEqualTo(25_000);
    // Quatro por US$ 400: US$ 100 cada. A venda de um leva US$ 100 do custo e rende US$ 50.
    assertThat(position.get("custoMedioUsd").asString()).isEqualTo("100");
    assertThat(position.get("custoDaPosicaoUsdCentavos").asLong()).isEqualTo(30_000);
    assertThat(position.get("ganhoUsdCentavos").asLong()).isEqualTo(6_000);
    assertThat(position.get("realizadoUsdCentavos").asLong()).isEqualTo(5_000);
    // Em reais o custo vai pela PTAX de cada movimento (5) e o valor pela da data (5,5): a
    // valorização em reais conta também o dólar que subiu.
    assertThat(position.get("valorCentavos").asLong()).isEqualTo(198_000);
    assertThat(position.get("custoDaPosicaoCentavos").asLong()).isEqualTo(150_000);
    assertThat(position.get("ganhoCentavos").asLong()).isEqualTo(48_000);
    assertThat(position.get("realizadoCentavos").asLong()).isEqualTo(25_000);
  }

  // A conferência da venda soma o dia inteiro: no mesmo dia a compra entra antes da venda, mesmo
  // gravada depois.
  @Test
  void onTheSameDayBuysComeBeforeSales() throws Exception {
    move("compra", "3", "2037-06-02", 30_000);
    move("venda", "3", "2037-06-06", 60_000);
    move("compra", "1", "2037-06-06", 5_000);

    JsonNode position = onlyPosition();

    // Quatro por US$ 350: a venda de três leva US$ 262,50 e sobra US$ 87,50.
    assertThat(position.get("custoMedioUsd").asString()).isEqualTo("87.5");
    assertThat(position.get("custoDaPosicaoUsdCentavos").asLong()).isEqualTo(8_750);
    assertThat(position.get("realizadoUsdCentavos").asLong()).isEqualTo(33_750);
  }

  @Test
  void withoutAQuoteThereIsCostButNoGain() throws Exception {
    move("compra", "1", "2037-06-02", 10_000);

    JsonNode position = onlyPosition();

    assertThat(position.get("custoDaPosicaoUsdCentavos").asLong()).isEqualTo(10_000);
    assertThat(position.get("ganhoUsdCentavos").isNull()).isTrue();
    assertThat(position.get("ganhoCentavos").isNull()).isTrue();
  }

  @Test
  void netWorthSplitsCryptoIntoContributionAndAppreciation() throws Exception {
    move("compra", "2", "2037-06-02", 10_000);
    move("compra", "2", "2037-06-03", 30_000);
    move("venda", "1", "2037-06-05", 15_000);
    api.quoteOk(actor.session(), bitcoin, "120", "2037-06-15T12:00:00Z");

    JsonNode netWorth = CryptoApi.expect(api.netWorth(actor.session(), "?data=2037-06-20"), 200);

    assertThat(netWorth.get("criptoCentavos").asLong()).isEqualTo(198_000);
    // Compras menos vendas em reais: 500 + 1.500 - 750.
    assertThat(netWorth.get("criptoAportadoCentavos").asLong()).isEqualTo(125_000);
    // O ganho em aberto (480) mais o realizado (250).
    assertThat(netWorth.get("criptoValorizacaoCentavos").asLong()).isEqualTo(73_000);
  }

  @Test
  void aPositionWithoutQuoteStaysOutOfTheSplit() throws Exception {
    move("compra", "1", "2037-06-02", 10_000);

    JsonNode netWorth = CryptoApi.expect(api.netWorth(actor.session(), "?data=2037-06-20"), 200);

    assertThat(netWorth.get("criptoCentavos").asLong()).isZero();
    assertThat(netWorth.get("criptoAportadoCentavos").asLong()).isZero();
    assertThat(netWorth.get("criptoValorizacaoCentavos").asLong()).isZero();
  }
}
