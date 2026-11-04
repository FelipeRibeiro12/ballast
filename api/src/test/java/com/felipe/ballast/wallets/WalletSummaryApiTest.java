package com.felipe.ballast.wallets;

import static com.felipe.ballast.FakeWalletIndexer.nativeHolding;
import static com.felipe.ballast.crypto.CryptoApi.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.FakeWalletIndexer;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.crypto.CryptoApi;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Roda só com Docker (Testcontainers): GET /carteiras/resumo e o corte de poeira em GET /carteiras.
@IntegrationTest
class WalletSummaryApiTest {

  private static final String ADDRESS_A = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";
  private static final String ADDRESS_B = "0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359";
  private static final Instant NOW = Instant.parse("2026-11-04T15:00:00Z");
  // Depois de qualquer relógio de teste: a última leitura do USDC é esta, sem afetar quem usa
  // limite anterior.
  private static final OffsetDateTime DOLLAR_MOMENT = OffsetDateTime.parse("2099-01-01T00:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  @Autowired FakeWalletIndexer indexer;
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
    jdbc.sql("DELETE FROM cotacao_usdc_brl WHERE momento = :m").param("m", DOLLAR_MOMENT).update();
  }

  @Test
  void summarizesTokensAndDefiWithTheCutoffAndTheDailyChange() throws Exception {
    CryptoApi.Actor actor = api.newActor();
    String walletId = syncedWallet(actor, ADDRESS_A, "2");
    String ethId = firstAssetId(actor);
    api.quoteOk(actor.session(), ethId, "90", "2026-11-03T09:00:00Z");
    api.quoteOk(actor.session(), ethId, "100", "2026-11-04T14:00:00Z");
    defi(actor, walletId, "defi", "deposito", 5000L, -100L);
    defi(actor, walletId, "defi", "emprestimo", -3000L, 50L);
    defi(actor, walletId, "defi", "deposito", 49L, 10L);
    defi(actor, walletId, "defi", "deposito", null, null);
    defi(actor, walletId, "nft", "nft", 99999L, null);

    JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);

    assertThat(summary.get("carteiras")).hasSize(1);
    JsonNode wallet = summary.get("carteiras").get(0);
    assertThat(wallet.get("id").asString()).isEqualTo(walletId);
    assertThat(wallet.get("rede").asString()).isEqualTo("ethereum");
    // 2 ETH a US$ 100 (20000) mais depósito (5000) menos empréstimo (3000).
    assertThat(wallet.get("valorUsdCentavos").asLong()).isEqualTo(22000L);
    assertThat(wallet.get("valorBrlCentavos").asLong()).isEqualTo(110000L);
    // 2 x (100 - 90) = 2000, menos 100, mais 50.
    assertThat(wallet.get("variacao24hUsdCentavos").asLong()).isEqualTo(1950L);
    assertThat(wallet.get("variacao24hBrlCentavos").asLong()).isEqualTo(9750L);
    assertThat(wallet.get("variacao24hPercentual").asString()).isEqualTo("9.73");
    assertThat(wallet.get("variacaoParcial").asBoolean()).isFalse();
    JsonNode total = summary.get("total");
    assertThat(total.get("valorUsdCentavos").asLong()).isEqualTo(22000L);
    assertThat(total.get("valorBrlCentavos").asLong()).isEqualTo(110000L);
    assertThat(total.get("variacao24hUsdCentavos").asLong()).isEqualTo(1950L);
    assertThat(total.get("variacao24hPercentual").asString()).isEqualTo("9.73");
  }

  @Test
  void aTokenWithoutAQuoteFrom24HoursAgoMarksTheChangeAsPartial() throws Exception {
    CryptoApi.Actor actor = api.newActor();
    syncedWallet(actor, ADDRESS_A, "1");
    api.quoteOk(actor.session(), firstAssetId(actor), "100", "2026-11-04T14:00:00Z");

    JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);

    JsonNode wallet = summary.get("carteiras").get(0);
    assertThat(wallet.get("valorUsdCentavos").asLong()).isEqualTo(10000L);
    assertThat(wallet.get("variacao24hUsdCentavos").asLong()).isZero();
    assertThat(wallet.get("variacaoParcial").asBoolean()).isTrue();
    assertThat(summary.get("total").get("variacaoParcial").asBoolean()).isTrue();
  }

  @Test
  void aSyncedTokenBelowFiftyCentsIsLeftOutAndTheDefiCutoffAppliesOnTheWalletRoute()
      throws Exception {
    CryptoApi.Actor actor = api.newActor();
    String walletId = syncedWallet(actor, ADDRESS_A, "1");
    api.quoteOk(actor.session(), firstAssetId(actor), "0.49", "2026-11-04T14:00:00Z");
    defi(actor, walletId, "defi", "deposito", 49L, null);
    defi(actor, walletId, "defi", "deposito", 50L, null);
    defi(actor, walletId, "defi", "emprestimo", -30L, null);
    defi(actor, walletId, "defi", "deposito", null, null);
    defi(actor, walletId, "nft", "nft", 1L, null);

    JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);
    JsonNode detail = expect(api.send(actor.session(), get("/carteiras/" + walletId), null), 200);

    assertThat(summary.get("total").get("valorUsdCentavos").asLong()).isEqualTo(50L);
    // Sobram o DeFi de 0,50, o sem valor e o NFT; o retrato gravado segue completo.
    assertThat(detail.get("posicoes")).hasSize(3);
    assertThat(
            jdbc.sql("SELECT count(*) FROM posicao_carteira WHERE carteira_id = :w")
                .param("w", UUID.fromString(walletId))
                .query(Long.class)
                .single())
        .isEqualTo(5L);
  }

  @Test
  void theSummaryOfOneUserNeverHasTheWalletOfAnother() throws Exception {
    CryptoApi.Actor alice = api.newActor();
    CryptoApi.Actor bob = api.newActor();
    String aliceWallet = syncedWallet(alice, ADDRESS_A, "1");
    String bobWallet = syncedWallet(bob, ADDRESS_B, "1");
    defi(alice, aliceWallet, "defi", "deposito", 10000L, 100L);
    defi(bob, bobWallet, "defi", "deposito", 777700L, 5L);

    JsonNode aliceSummary = expect(api.send(alice.session(), get("/carteiras/resumo"), null), 200);
    JsonNode bobSummary = expect(api.send(bob.session(), get("/carteiras/resumo"), null), 200);

    assertThat(aliceSummary.get("carteiras")).hasSize(1);
    assertThat(aliceSummary.get("carteiras").get(0).get("id").asString()).isEqualTo(aliceWallet);
    assertThat(aliceSummary.get("total").get("valorUsdCentavos").asLong()).isEqualTo(10000L);
    assertThat(bobSummary.get("carteiras")).hasSize(1);
    assertThat(bobSummary.get("carteiras").get(0).get("id").asString()).isEqualTo(bobWallet);
    assertThat(bobSummary.get("total").get("valorUsdCentavos").asLong()).isEqualTo(777700L);
    assertThat(api.send(bob.session(), get("/carteiras/" + aliceWallet), null).getStatus())
        .isEqualTo(404);
    assertThat(
            api.send(bob.session(), post("/carteiras/" + aliceWallet + "/sincronizar"), null)
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  void aUserWithoutWalletsGetsAnEmptySummaryAndASessionIsRequired() throws Exception {
    CryptoApi.Actor actor = api.newActor();

    JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);

    assertThat(summary.get("carteiras")).isEmpty();
    assertThat(summary.get("total").get("valorUsdCentavos").asLong()).isZero();
    assertThat(summary.get("total").get("variacao24hPercentual").isNull()).isTrue();
    assertThat(api.send(null, get("/carteiras/resumo"), null).getStatus()).isEqualTo(401);
  }

  @Test
  void responseFieldsMatchTheOpenApiSchema() throws Exception {
    CryptoApi.Actor actor = api.newActor();
    syncedWallet(actor, ADDRESS_A, "1");
    JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);
    JsonNode schemas =
        CryptoApi.body(api.send(null, get("/v3/api-docs"), null)).get("components").get("schemas");

    assertThat(names(summary)).isEqualTo(names(schemas.get("ResumoCarteiras").get("properties")));
    assertThat(names(summary.get("carteiras").get(0)))
        .isEqualTo(names(schemas.get("ResumoCarteira").get("properties")));
    assertThat(names(summary.get("total")))
        .isEqualTo(names(schemas.get("ResumoCarteirasTotal").get("properties")));
  }

  private String syncedWallet(CryptoApi.Actor actor, String address, String quantity)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaId", actor.cryptoId());
    body.put("rede", "ethereum");
    body.put("endereco", address);
    String id =
        expect(api.send(actor.session(), post("/carteiras"), body), 201).get("id").asString();
    indexer.publish(WalletNetwork.ETHEREUM, address, nativeHolding(quantity));
    expect(api.send(actor.session(), post("/carteiras/" + id + "/sincronizar"), null), 200);
    return id;
  }

  private String firstAssetId(CryptoApi.Actor actor) throws Exception {
    MockHttpServletResponse response = api.listAssets(actor.session());
    return expect(response, 200).get(0).get("id").asString();
  }

  private void defi(
      CryptoApi.Actor actor,
      String walletId,
      String kind,
      String category,
      Long value,
      Long change) {
    jdbc.sql(
            """
            INSERT INTO posicao_carteira
              (id, usuario_id, carteira_id, tipo, categoria, nome, valor_usd_centavos,
               variacao_24h_usd_centavos)
            VALUES (:id, :user, :wallet, :kind, :category, 'posicao', :value, :change)
            """)
        .param("id", UUID.randomUUID())
        .param("user", actor.user().id())
        .param("wallet", UUID.fromString(walletId))
        .param("kind", kind)
        .param("category", category)
        .param("value", value)
        .param("change", change)
        .update();
  }

  private static java.util.Set<String> names(JsonNode node) {
    java.util.Set<String> names = new java.util.TreeSet<>();
    node.propertyNames().forEach(names::add);
    return names;
  }
}
