package com.felipe.ballast.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// Sem rede: o servidor simulado do Spring responde no lugar da CoinGecko.
class CoinGeckoQuoteSourceTest {

  private static final String BASE = "https://api.coingecko.com/api/v3";
  private static final String USDC_BASE = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";
  private static final String BONK = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263";

  private MockRestServiceServer server;
  private CoinGeckoQuoteSource source;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder =
        RestClient.builder().baseUrl(BASE).defaultHeader("x-cg-demo-api-key", "chave-de-teste");
    server = MockRestServiceServer.bindTo(builder).build();
    source = new CoinGeckoQuoteSource(builder.build());
  }

  @Test
  void asksTheNativeCoinsInOneCallAndReadsThePriceWithoutDouble() {
    server
        .expect(
            requestTo(
                BASE
                    + "/simple/price?ids=bitcoin%2Cethereum&vs_currencies=usd"
                    + "&include_last_updated_at=true&precision=full"))
        .andExpect(header("x-cg-demo-api-key", "chave-de-teste"))
        .andRespond(
            withSuccess(
                // 0.1 + 0.2 em double vira 0.30000000000000004: o texto tem de chegar inteiro.
                "{\"bitcoin\":{\"usd\":76975.123456789,\"last_updated_at\":1792700000},"
                    + "\"ethereum\":{\"usd\":0.30000000000000004,\"last_updated_at\":1792700100}}",
                MediaType.APPLICATION_JSON));

    Map<AssetRef, ProviderQuote> quotes =
        source.fetch(
            List.of(
                new AssetRef(CryptoNetwork.BITCOIN, null),
                new AssetRef(CryptoNetwork.ETHEREUM, null),
                // ETH na Base e na Arbitrum é o mesmo ether: entra no pedido uma vez só.
                new AssetRef(CryptoNetwork.BASE, null),
                new AssetRef(CryptoNetwork.ARBITRUM, null)));

    assertThat(quotes.get(new AssetRef(CryptoNetwork.BITCOIN, null)))
        .isEqualTo(
            new ProviderQuote(
                new BigDecimal("76975.123456789"), Instant.ofEpochSecond(1792700000)));
    assertThat(quotes.get(new AssetRef(CryptoNetwork.ETHEREUM, null)).priceUsd())
        .isEqualByComparingTo("0.30000000000000004");
    assertThat(quotes.get(new AssetRef(CryptoNetwork.BASE, null)).priceUsd())
        .isEqualByComparingTo("0.30000000000000004");
    assertThat(quotes.get(new AssetRef(CryptoNetwork.ARBITRUM, null)).updatedAt())
        .isEqualTo(Instant.ofEpochSecond(1792700100));
    server.verify();
  }

  @Test
  void asksTokensByPlatformAndContract() {
    server
        .expect(
            requestTo(
                BASE
                    + "/simple/token_price/base?contract_addresses="
                    + USDC_BASE
                    + "&vs_currencies=usd&include_last_updated_at=true&precision=full"))
        .andRespond(
            withSuccess(
                "{\"" + USDC_BASE + "\":{\"usd\":0.999812,\"last_updated_at\":1792700000}}",
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                BASE
                    + "/simple/token_price/solana?contract_addresses="
                    + BONK
                    + "&vs_currencies=usd&include_last_updated_at=true&precision=full"))
        .andRespond(
            withSuccess(
                // A chave volta comparada sem caixa: o EVM a CoinGecko devolve em minúsculas.
                "{\""
                    + BONK.toLowerCase()
                    + "\":{\"usd\":1.234E-5,\"last_updated_at\":1792700000}}",
                MediaType.APPLICATION_JSON));

    Map<AssetRef, ProviderQuote> quotes =
        source.fetch(
            List.of(
                new AssetRef(CryptoNetwork.BASE, USDC_BASE),
                new AssetRef(CryptoNetwork.SOLANA, BONK)));

    assertThat(quotes.get(new AssetRef(CryptoNetwork.BASE, USDC_BASE)).priceUsd())
        .isEqualByComparingTo("0.999812");
    assertThat(quotes.get(new AssetRef(CryptoNetwork.SOLANA, BONK)).priceUsd())
        .isEqualByComparingTo("0.00001234");
    server.verify();
  }

  @Test
  void leavesOutWhatTheProviderDoesNotKnowOrSendsBroken() {
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("/simple/price")))
        .andRespond(
            withSuccess(
                "{\"bitcoin\":{\"usd\":\"muito\",\"last_updated_at\":1792700000},"
                    + "\"solana\":{\"usd\":-3,\"last_updated_at\":1792700000},"
                    + "\"ethereum\":{\"usd\":3000}}",
                MediaType.APPLICATION_JSON));

    Map<AssetRef, ProviderQuote> quotes =
        source.fetch(
            List.of(
                new AssetRef(CryptoNetwork.BITCOIN, null),
                new AssetRef(CryptoNetwork.ETHEREUM, null),
                new AssetRef(CryptoNetwork.SOLANA, null),
                new AssetRef(CryptoNetwork.POLYGON, null)));

    // Texto no lugar do número, preço negativo, sem data e ativo que não veio: nenhum entra.
    assertThat(quotes).isEmpty();
  }

  @Test
  void nothingToAskMeansNoCall() {
    server.expect(never(), requestTo(org.hamcrest.Matchers.anything()));

    assertThat(source.fetch(List.of())).isEmpty();
    server.verify();
  }

  @Test
  void aProviderErrorComesUpAsAnException() {
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("/simple/price")))
        .andRespond(withServerError());

    assertThatThrownBy(() -> source.fetch(List.of(new AssetRef(CryptoNetwork.BITCOIN, null))))
        .isInstanceOf(RuntimeException.class);
  }

  private static final Instant FROM = Instant.ofEpochSecond(1761350400);
  private static final Instant TO = Instant.ofEpochSecond(1792713600);

  @Test
  void asksTheDailyHistoryOfANativeCoinByIdAndKeepsOnlyValidPoints() {
    server
        .expect(
            requestTo(
                BASE
                    + "/coins/polygon-ecosystem-token/market_chart/range?vs_currency=usd"
                    + "&from=1761350400&to=1792713600&precision=full"))
        .andExpect(header("x-cg-demo-api-key", "chave-de-teste"))
        .andRespond(
            withSuccess(
                "{\"prices\":[[1761350400000,0.30000000000000004],[1761436800000,\"x\"],"
                    + "[1761523200000,-1],[1761609600000],[1761696000000,0.2]],"
                    + "\"market_caps\":[],\"total_volumes\":[]}",
                MediaType.APPLICATION_JSON));

    List<ProviderQuote> history =
        source.history(new AssetRef(CryptoNetwork.POLYGON, null), FROM, TO);

    // Texto, negativo e ponto sem preço ficam de fora; o resto chega sem passar por double.
    assertThat(history)
        .containsExactly(
            new ProviderQuote(
                new BigDecimal("0.30000000000000004"), Instant.ofEpochMilli(1761350400000L)),
            new ProviderQuote(new BigDecimal("0.2"), Instant.ofEpochMilli(1761696000000L)));
    server.verify();
  }

  @Test
  void asksTheHistoryOfATokenByPlatformAndContract() {
    server
        .expect(
            requestTo(
                BASE
                    + "/coins/base/contract/"
                    + USDC_BASE
                    + "/market_chart/range?vs_currency=usd&from=1761350400&to=1792713600"
                    + "&precision=full"))
        .andRespond(
            withSuccess("{\"prices\":[[1761350400000,0.9999]]}", MediaType.APPLICATION_JSON));

    assertThat(source.history(new AssetRef(CryptoNetwork.BASE, USDC_BASE), FROM, TO))
        .extracting(ProviderQuote::priceUsd)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(new BigDecimal("0.9999"));
    server.verify();
  }

  @Test
  void aTokenTheProviderDoesNotKnowHasNoHistory() {
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("/market_chart/range")))
        .andRespond(withResourceNotFound().body("{\"error\":\"coin not found\"}"));

    assertThat(source.history(new AssetRef(CryptoNetwork.ETHEREUM, USDC_BASE), FROM, TO)).isEmpty();
  }

  @Test
  void aProviderErrorInTheHistoryComesUpAsAnException() {
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("/market_chart/range")))
        .andRespond(withServerError());

    assertThatThrownBy(() -> source.history(new AssetRef(CryptoNetwork.BITCOIN, null), FROM, TO))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void readsTheUsdcInReaisWithoutDouble() {
    server
        .expect(
            requestTo(
                BASE
                    + "/simple/price?ids=usd-coin&vs_currencies=brl"
                    + "&include_last_updated_at=true&precision=full"))
        .andExpect(header("x-cg-demo-api-key", "chave-de-teste"))
        .andRespond(
            withSuccess(
                "{\"usd-coin\":{\"brl\":5.412345678901,\"last_updated_at\":1792700000}}",
                MediaType.APPLICATION_JSON));

    assertThat(source.usdcBrl())
        .contains(
            new UsdcQuote(new BigDecimal("5.412345678901"), Instant.ofEpochSecond(1792700000)));
    server.verify();
  }

  @Test
  void aZeroNegativeOrMissingUsdcPriceIsIgnored() {
    for (String body :
        List.of(
            "{\"usd-coin\":{\"brl\":0,\"last_updated_at\":1792700000}}",
            "{\"usd-coin\":{\"brl\":-5,\"last_updated_at\":1792700000}}",
            "{\"usd-coin\":{\"brl\":\"x\",\"last_updated_at\":1792700000}}",
            "{\"usd-coin\":{\"last_updated_at\":1792700000}}",
            "{\"usd-coin\":{\"brl\":5.4}}",
            "{}")) {
      server.reset();
      server
          .expect(requestTo(org.hamcrest.Matchers.containsString("/simple/price?ids=usd-coin")))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

      assertThat(source.usdcBrl()).as(body).isEmpty();
    }
  }

  @Test
  void aProviderErrorInTheUsdcComesUpAsAnException() {
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("/simple/price?ids=usd-coin")))
        .andRespond(withServerError());

    assertThatThrownBy(() -> source.usdcBrl()).isInstanceOf(RuntimeException.class);
  }

  @Test
  void asksTheUsdcHistoryInReaisAndKeepsOnlyValidPoints() {
    server
        .expect(
            requestTo(
                BASE
                    + "/coins/usd-coin/market_chart/range?vs_currency=brl"
                    + "&from=1761350400&to=1792713600&precision=full"))
        .andExpect(header("x-cg-demo-api-key", "chave-de-teste"))
        .andRespond(
            withSuccess(
                "{\"prices\":[[1761350400000,5.30000000000000004],[1761436800000,0],"
                    + "[1761523200000,\"x\"],[1761609600000],[1761696000000,5.5]]}",
                MediaType.APPLICATION_JSON));

    assertThat(source.usdcBrlHistory(FROM, TO))
        .containsExactly(
            new UsdcQuote(
                new BigDecimal("5.30000000000000004"), Instant.ofEpochMilli(1761350400000L)),
            new UsdcQuote(new BigDecimal("5.5"), Instant.ofEpochMilli(1761696000000L)));
    server.verify();
  }
}
