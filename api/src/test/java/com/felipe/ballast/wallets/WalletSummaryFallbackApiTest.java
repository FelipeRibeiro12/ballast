package com.felipe.ballast.wallets;

import static com.felipe.ballast.FakeWalletIndexer.nativeHolding;
import static com.felipe.ballast.crypto.CryptoApi.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.FakeQuoteSource;
import com.felipe.ballast.FakeWalletIndexer;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.crypto.CryptoApi;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Zerion caindo e CoinGecko fora do ar não derrubam o saldo nem o resumo: vale o último retrato e a
// última cotação gravados.
@IntegrationTest
@Import(FakePositionsProvider.Config.class)
class WalletSummaryFallbackApiTest {

  private static final Instant NOW = Instant.parse("2026-11-04T15:00:00Z");
  private static final String ADDRESS = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";
  private static final String TODAY_QUOTE = "2026-11-04T14:00:00Z";
  // Dentro do dia: o patrimônio busca o dólar até a data pedida, então uma cotação futura não
  // serve.
  private static final java.time.OffsetDateTime DOLLAR_MOMENT =
      java.time.OffsetDateTime.parse("2026-11-04T13:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  @Autowired FakeWalletIndexer indexer;
  @Autowired FakePositionsProvider positions;
  @Autowired FakeQuoteSource quotes;
  @Autowired JdbcClient jdbc;

  private CryptoApi api;

  @BeforeEach
  void setUp() {
    clock.freeze(NOW);
    api = new CryptoApi(mvc);
    jdbc.sql("INSERT INTO cotacao_usdc_brl (momento, preco) VALUES (:m, 5.00)")
        .param("m", DOLLAR_MOMENT)
        .update();
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    indexer.reset();
    positions.reset();
    quotes.reset();
    jdbc.sql("DELETE FROM cotacao_usdc_brl WHERE momento = :m").param("m", DOLLAR_MOMENT).update();
  }

  @Test
  void aZerionFailureKeepsTheLastSnapshotWhileTheBalanceStillMoves() throws Exception {
    CryptoApi.Actor actor = api.newActor();
    String wallet = createWallet(actor);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1"));
    sync(actor, wallet);
    api.quoteOk(actor.session(), firstAssetId(actor), "100", TODAY_QUOTE);
    positions.answer(List.of(defiPosition(5000L, 200L)));
    clock.advance(Duration.ofMinutes(2));
    sync(actor, wallet);
    assertThat(total(actor).get("valorUsdCentavos").asLong()).isEqualTo(15000L);

    // Zerion cai: o saldo sobe para 2 ETH e o DeFi gravado antes continua.
    positions.mode(FakePositionsProvider.Mode.THROW);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("2"));
    clock.advance(Duration.ofMinutes(2));
    sync(actor, wallet);
    assertThat(total(actor).get("valorUsdCentavos").asLong()).isEqualTo(25000L);
    JsonNode detail = expect(api.send(actor.session(), get("/carteiras/" + wallet), null), 200);
    assertThat(detail.get("posicoes")).hasSize(1);
    assertThat(detail.get("posicoes").get(0).get("valorUsdCentavos").asLong()).isEqualTo(5000L);

    // Zerion responde vazio (falha que não é exceção): mesmo resultado.
    positions.mode(FakePositionsProvider.Mode.EMPTY);
    clock.advance(Duration.ofMinutes(2));
    sync(actor, wallet);
    assertThat(total(actor).get("valorUsdCentavos").asLong()).isEqualTo(25000L);
  }

  @Test
  void aCoinGeckoOutageLeavesTheSummaryAndThePatrimonioAnswering() throws Exception {
    CryptoApi.Actor actor = api.newActor();
    String wallet = createWallet(actor);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1"));
    sync(actor, wallet);
    api.quoteOk(actor.session(), firstAssetId(actor), "100", TODAY_QUOTE);
    positions.answer(List.of(defiPosition(5000L, null)));
    clock.advance(Duration.ofMinutes(2));
    sync(actor, wallet);

    quotes.goDown();

    assertThat(total(actor).get("valorUsdCentavos").asLong()).isEqualTo(15000L);
    JsonNode today = expect(api.send(actor.session(), get("/patrimonio"), null), 200);
    assertThat(today.get("defiUsdCentavos").asLong()).isEqualTo(5000L);
  }

  private String createWallet(CryptoApi.Actor actor) throws Exception {
    Map<String, Object> body =
        Map.of("contaId", actor.cryptoId(), "rede", "ethereum", "endereco", ADDRESS);
    return expect(api.send(actor.session(), post("/carteiras"), body), 201).get("id").asString();
  }

  private JsonNode sync(CryptoApi.Actor actor, String wallet) throws Exception {
    return expect(
        api.send(actor.session(), post("/carteiras/" + wallet + "/sincronizar"), null), 200);
  }

  private JsonNode total(CryptoApi.Actor actor) throws Exception {
    return expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200).get("total");
  }

  private String firstAssetId(CryptoApi.Actor actor) throws Exception {
    return expect(api.listAssets(actor.session()), 200).get(0).get("id").asString();
  }

  private static PositionsProvider.Position defiPosition(long value, Long change) {
    return new PositionsProvider.Position(
        "defi", "deposito", "Aave", "Supply USDC", null, BigDecimal.ONE, value, change);
  }
}
