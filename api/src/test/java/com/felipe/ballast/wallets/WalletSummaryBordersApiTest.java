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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Bordas de GET /carteiras/resumo: corte de US$ 0,50, variação de 24 h e soma por carteira. Cada
// caso usa um usuário novo, porque a cotação do ativo é por usuário.
@IntegrationTest
class WalletSummaryBordersApiTest {

  private static final Instant NOW = Instant.parse("2026-11-04T15:00:00Z");
  private static final String TODAY_QUOTE = "2026-11-04T14:00:00Z";
  // Exatamente 24 h antes de NOW: entra na variação.
  private static final String DAY_AGO_EXACT = "2026-11-03T15:00:00Z";
  private static final String DAY_AGO_ONE_SECOND_SHORT = "2026-11-03T15:00:01Z";
  private static final String DAY_AGO_BEFORE = "2026-11-03T14:59:59Z";
  private static final OffsetDateTime DOLLAR_MOMENT = OffsetDateTime.parse("2099-01-02T00:00:00Z");
  private static final String SOLANA_ADDRESS = "11111111111111111111111111111111";

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  @Autowired FakeWalletIndexer indexer;
  @Autowired JdbcClient jdbc;

  private CryptoApi api;
  private int addressSeed;

  @BeforeEach
  void setUp() {
    clock.freeze(NOW);
    api = new CryptoApi(mvc);
    // Dólar a 5,00 por toda a janela de teste; a leitura pega o último momento.
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
  void tokenCutoffIsFiftyCentsAndMeasuredOnTheFullValue() throws Exception {
    assertThat(tokenUsdCentavos("1", "0.49")).isZero();
    assertThat(tokenUsdCentavos("1", "0.50")).isEqualTo(50L);
    assertThat(tokenUsdCentavos("1", "0.51")).isEqualTo(51L);
    // 0,4998 arredondado para centavos daria 0,50 e passaria pelo corte.
    assertThat(tokenUsdCentavos("0.2499", "2")).isZero();
    assertThat(tokenUsdCentavos("0.25", "2")).isEqualTo(50L);
  }

  @Test
  void defiCutoffIsFiftyCentsInModuleAndLoansFollowTheSameRule() throws Exception {
    CryptoApi.Actor actor = api.newActor();
    String wallet = createWallet(actor, "ethereum", nextAddress());
    defi(actor, wallet, "deposito", 49L, null);
    defi(actor, wallet, "emprestimo", -49L, null);
    defi(actor, wallet, "deposito", 50L, null);

    JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);

    assertThat(byId(summary, wallet).get("valorUsdCentavos").asLong()).isEqualTo(50L);

    CryptoApi.Actor loan = api.newActor();
    String loanWallet = createWallet(loan, "ethereum", nextAddress());
    defi(loan, loanWallet, "emprestimo", -50L, null);
    JsonNode loanSummary = expect(api.send(loan.session(), get("/carteiras/resumo"), null), 200);
    assertThat(byId(loanSummary, loanWallet).get("valorUsdCentavos").asLong()).isEqualTo(-50L);
  }

  @Test
  void eachWalletRoundsOnceAndTheTotalIsTheSumOfTheRoundedWallets() throws Exception {
    CryptoApi.Actor actor = api.newActor();
    // Cada carteira vale 33,33333... dólares e 166,666... reais: o arredondamento pesa em cada uma.
    for (int i = 0; i < 3; i++) {
      syncedWallet(actor, "0.333333333333333333");
    }
    api.quoteOk(actor.session(), firstAssetId(actor), "100", TODAY_QUOTE);

    JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);

    long usdSum = 0;
    long brlSum = 0;
    for (JsonNode wallet : summary.get("carteiras")) {
      assertThat(wallet.get("valorUsdCentavos").asLong()).isEqualTo(3333L);
      assertThat(wallet.get("valorBrlCentavos").asLong()).isEqualTo(16667L);
      usdSum += wallet.get("valorUsdCentavos").asLong();
      brlSum += wallet.get("valorBrlCentavos").asLong();
    }
    JsonNode total = summary.get("total");
    assertThat(total.get("valorUsdCentavos").asLong()).isEqualTo(usdSum).isEqualTo(9999L);
    // Arredondar o total de uma vez daria 50000. A tela soma as linhas, então o total segue a soma.
    assertThat(total.get("valorBrlCentavos").asLong()).isEqualTo(brlSum).isEqualTo(50001L);
  }

  @Test
  void aQuoteExactlyTwentyFourHoursOldCountsAndAFlatPriceIsZeroChange() throws Exception {
    CryptoApi.Actor actor = api.newActor();
    String wallet = syncedWallet(actor, "1");
    api.quoteOk(actor.session(), firstAssetId(actor), "100", DAY_AGO_EXACT);
    api.quoteOk(actor.session(), firstAssetId(actor), "100", TODAY_QUOTE);

    JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);

    JsonNode found = byId(summary, wallet);
    assertThat(found.get("valorUsdCentavos").asLong()).isEqualTo(10000L);
    assertThat(found.get("variacao24hUsdCentavos").asLong()).isZero();
    assertThat(found.get("variacao24hPercentual").asString()).isEqualTo("0.00");
    assertThat(found.get("variacaoParcial").asBoolean()).isFalse();
  }

  @Test
  void aQuoteOneSecondYoungerThanTwentyFourHoursLeavesTheTokenOutOfTheChange() throws Exception {
    CryptoApi.Actor actor = api.newActor();
    String wallet = syncedWallet(actor, "1");
    api.quoteOk(actor.session(), firstAssetId(actor), "100", DAY_AGO_ONE_SECOND_SHORT);
    api.quoteOk(actor.session(), firstAssetId(actor), "80", TODAY_QUOTE);

    JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);

    JsonNode found = byId(summary, wallet);
    assertThat(found.get("valorUsdCentavos").asLong()).isEqualTo(8000L);
    assertThat(found.get("variacao24hUsdCentavos").asLong()).isZero();
    assertThat(found.get("variacaoParcial").asBoolean()).isTrue();
    assertThat(summary.get("total").get("variacaoParcial").asBoolean()).isTrue();
  }

  @Test
  void changeKeepsItsSignAndThePercentIsNullWhenTheBaseIsNotPositive() throws Exception {
    CryptoApi.Actor actor = api.newActor();
    String ethWallet = syncedWallet(actor, "1");
    api.quoteOk(actor.session(), firstAssetId(actor), "100", DAY_AGO_BEFORE);
    api.quoteOk(actor.session(), firstAssetId(actor), "80", TODAY_QUOTE);
    String loanWallet = createWallet(actor, "ethereum", nextAddress());
    defi(actor, loanWallet, "emprestimo", -100L, 0L);
    String depositWallet = createWallet(actor, "ethereum", nextAddress());
    defi(actor, depositWallet, "deposito", 100L, 100L);

    JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);

    JsonNode eth = byId(summary, ethWallet);
    assertThat(eth.get("valorUsdCentavos").asLong()).isEqualTo(8000L);
    assertThat(eth.get("variacao24hUsdCentavos").asLong()).isEqualTo(-2000L);
    assertThat(eth.get("variacao24hPercentual").asString()).isEqualTo("-20.00");

    JsonNode loan = byId(summary, loanWallet);
    assertThat(loan.get("valorUsdCentavos").asLong()).isEqualTo(-100L);
    assertThat(loan.get("variacao24hUsdCentavos").asLong()).isZero();
    // Base de 24 h atrás = -100, não positiva: sem percentual.
    assertThat(loan.get("variacao24hPercentual").isNull()).isTrue();

    JsonNode deposit = byId(summary, depositWallet);
    assertThat(deposit.get("variacao24hUsdCentavos").asLong()).isEqualTo(100L);
    // Base de 24 h atrás = 0: sem percentual.
    assertThat(deposit.get("variacao24hPercentual").isNull()).isTrue();

    JsonNode total = summary.get("total");
    assertThat(total.get("valorUsdCentavos").asLong()).isEqualTo(8000L);
    assertThat(total.get("variacao24hUsdCentavos").asLong()).isEqualTo(-1900L);
    // -1900 sobre 9900 (valor de 24 h atrás), arredondado em duas casas.
    assertThat(total.get("variacao24hPercentual").asString()).isEqualTo("-19.19");
    assertThat(total.get("variacaoParcial").asBoolean()).isFalse();
  }

  @Test
  void solanaWalletShowsItsDefiAndItsChangeInTheSummary() throws Exception {
    CryptoApi.Actor actor = api.newActor();
    String wallet = createWallet(actor, "solana", SOLANA_ADDRESS);
    defi(actor, wallet, "deposito", 4200L, 42L);

    JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);

    JsonNode found = byId(summary, wallet);
    assertThat(found.get("rede").asString()).isEqualTo("solana");
    assertThat(found.get("valorUsdCentavos").asLong()).isEqualTo(4200L);
    assertThat(found.get("valorBrlCentavos").asLong()).isEqualTo(21000L);
    assertThat(found.get("variacao24hUsdCentavos").asLong()).isEqualTo(42L);
  }

  private long tokenUsdCentavos(String quantity, String price) throws Exception {
    CryptoApi.Actor actor = api.newActor();
    String wallet = syncedWallet(actor, quantity);
    api.quoteOk(actor.session(), firstAssetId(actor), price, TODAY_QUOTE);
    JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);
    return byId(summary, wallet).get("valorUsdCentavos").asLong();
  }

  private String syncedWallet(CryptoApi.Actor actor, String quantity) throws Exception {
    String address = nextAddress();
    String id = createWallet(actor, "ethereum", address);
    indexer.publish(WalletNetwork.ETHEREUM, address, nativeHolding(quantity));
    expect(api.send(actor.session(), post("/carteiras/" + id + "/sincronizar"), null), 200);
    return id;
  }

  private String createWallet(CryptoApi.Actor actor, String network, String address)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaId", actor.cryptoId());
    body.put("rede", network);
    body.put("endereco", address);
    return expect(api.send(actor.session(), post("/carteiras"), body), 201).get("id").asString();
  }

  private String firstAssetId(CryptoApi.Actor actor) throws Exception {
    return expect(api.listAssets(actor.session()), 200).get(0).get("id").asString();
  }

  private void defi(
      CryptoApi.Actor actor, String walletId, String category, Long value, Long change) {
    jdbc.sql(
            """
            INSERT INTO posicao_carteira
              (id, usuario_id, carteira_id, tipo, categoria, nome, valor_usd_centavos,
               variacao_24h_usd_centavos)
            VALUES (:id, :user, :wallet, 'defi', :category, 'posicao', :value, :change)
            """)
        .param("id", UUID.randomUUID())
        .param("user", actor.user().id())
        .param("wallet", UUID.fromString(walletId))
        .param("category", category)
        .param("value", value)
        .param("change", change)
        .update();
  }

  private String nextAddress() {
    addressSeed++;
    return String.format("0x%040x", addressSeed);
  }

  private static JsonNode byId(JsonNode summary, String id) {
    for (JsonNode wallet : summary.get("carteiras")) {
      if (wallet.get("id").asString().equals(id)) {
        return wallet;
      }
    }
    throw new AssertionError("carteira " + id + " fora do resumo");
  }
}
