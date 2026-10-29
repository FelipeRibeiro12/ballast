package com.felipe.ballast.wallets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// Sem rede: o servidor simulado do Spring responde no lugar da Alchemy.
class AlchemyWalletIndexerTest {

  private static final String KEY = "chave-secreta-de-teste";
  private static final String URL = "http://alchemy.test/v2/" + KEY;
  private static final String ADDRESS = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";
  private static final String USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";

  private MockRestServiceServer server;
  private AlchemyWalletIndexer indexer;
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger rootLogger;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://alchemy.test");
    server = MockRestServiceServer.bindTo(builder).build();
    Map<WalletNetwork, RestClient> clients = new EnumMap<>(WalletNetwork.class);
    RestClient client = builder.build();
    for (WalletNetwork network : WalletNetwork.values()) {
      clients.put(network, client);
    }
    indexer = new AlchemyWalletIndexer(KEY, clients);
    rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    logs.start();
    rootLogger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logs);
    logs.stop();
  }

  @Test
  void readsTheNativeBalanceAndTheTokensOfAnEvmAddress() {
    server
        .expect(requestTo(URL))
        .andExpect(content().string(containsString("eth_getBalance")))
        .andRespond(json("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0xde0b6b3a7640000\"}"));
    server
        .expect(requestTo(URL))
        .andExpect(content().string(containsString("alchemy_getTokenBalances")))
        .andRespond(
            json(
                "{\"id\":1,\"result\":{\"address\":\"x\",\"tokenBalances\":["
                    + "{\"contractAddress\":\""
                    + USDC
                    + "\",\"tokenBalance\":\"0x0f4240\"},"
                    + "{\"contractAddress\":\"0x1111111111111111111111111111111111111111\","
                    + "\"tokenBalance\":\"0x0\"}]}}"));
    server
        .expect(requestTo(URL))
        .andExpect(content().string(containsString("alchemy_getTokenMetadata")))
        .andRespond(
            json(
                "[{\"id\":0,\"result\":{\"decimals\":6,\"symbol\":\"USDC\",\"name\":\"USD Coin\"}}]"));

    WalletIndexer.Reading reading = indexer.read(WalletNetwork.ETHEREUM, ADDRESS);

    assertThat(reading.partial()).isFalse();
    assertThat(reading.holdings()).hasSize(2);
    WalletIndexer.Holding nativeHolding = reading.holdings().get(0);
    assertThat(nativeHolding.contract()).isNull();
    assertThat(nativeHolding.quantity()).isEqualByComparingTo(new BigDecimal("1"));
    WalletIndexer.Holding usdc = reading.holdings().get(1);
    assertThat(usdc.contract()).isEqualTo(USDC);
    assertThat(usdc.symbol()).isEqualTo("USDC");
    assertThat(usdc.quantity()).isEqualByComparingTo(new BigDecimal("1"));
    server.verify();
  }

  @Test
  void theAddressTravelsInTheBodyNeverInTheUrl() {
    server
        .expect(requestTo(not(containsString(ADDRESS))))
        .andExpect(content().string(containsString(ADDRESS)))
        .andRespond(json("{\"id\":1,\"result\":\"0x0\"}"));
    server
        .expect(requestTo(not(containsString(ADDRESS))))
        .andRespond(json("{\"id\":1,\"result\":{\"tokenBalances\":[]}}"));

    indexer.read(WalletNetwork.BASE, ADDRESS);

    server.verify();
  }

  @Test
  void readsTheNativeBalanceAndTheTokensOfASolanaAddress() {
    server
        .expect(requestTo(URL))
        .andExpect(content().string(containsString("getBalance")))
        .andRespond(json("{\"id\":1,\"result\":{\"context\":{\"slot\":1},\"value\":2000000000}}"));
    server
        .expect(requestTo(URL))
        .andExpect(content().string(containsString("getTokenAccountsByOwner")))
        .andRespond(
            json(
                "{\"id\":1,\"result\":{\"value\":[{\"account\":{\"data\":{\"parsed\":{\"info\":"
                    + "{\"mint\":\"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v\","
                    + "\"tokenAmount\":{\"decimals\":6,\"uiAmountString\":\"12.5\"}}}}}}]}}"));
    server.expect(requestTo(URL)).andRespond(json("{\"id\":1,\"result\":{\"value\":[]}}"));

    WalletIndexer.Reading reading =
        indexer.read(WalletNetwork.SOLANA, "11111111111111111111111111111111");

    assertThat(reading.holdings()).hasSize(2);
    assertThat(reading.holdings().get(0).quantity()).isEqualByComparingTo(new BigDecimal("2"));
    assertThat(reading.holdings().get(1).quantity()).isEqualByComparingTo(new BigDecimal("12.5"));
    assertThat(reading.holdings().get(1).symbol()).isNull();
  }

  @Test
  void aServerErrorBecomesAnExceptionWithoutTheKeyOrACause() {
    server.expect(requestTo(URL)).andRespond(withServerError());

    assertThatThrownBy(() -> indexer.read(WalletNetwork.ETHEREUM, ADDRESS))
        .isInstanceOfSatisfying(
            IndexerUnavailableException.class,
            e -> {
              assertThat(e.reason()).isEqualTo(IndexerUnavailableException.Reason.UNAVAILABLE);
              assertThat(e.getCause()).isNull();
              assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain(ADDRESS);
              assertThat(e.getSuppressed()).isEmpty();
            });
    assertNothingSensitiveWasLogged();
  }

  @Test
  void aBrokenConnectionDoesNotLetTheClientExceptionEscape() {
    // A mensagem da exceção do cliente traz a URL, e a URL traz a chave.
    server
        .expect(requestTo(URL))
        .andRespond(
            request -> {
              throw new IOException("I/O error on POST request for \"" + URL + "\"");
            });

    assertThatThrownBy(() -> indexer.read(WalletNetwork.ETHEREUM, ADDRESS))
        .isExactlyInstanceOf(IndexerUnavailableException.class)
        .hasNoCause()
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY));
    assertNothingSensitiveWasLogged();
  }

  @Test
  void aBodyOutsideTheFormatIsAnInvalidResponse() {
    server.expect(requestTo(URL)).andRespond(json("isto não é json"));

    assertThatThrownBy(() -> indexer.read(WalletNetwork.ETHEREUM, ADDRESS))
        .isInstanceOfSatisfying(
            IndexerUnavailableException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(IndexerUnavailableException.Reason.INVALID_RESPONSE));
  }

  @Test
  void aBalanceThatIsNotHexadecimalIsAnInvalidResponse() {
    server.expect(requestTo(URL)).andRespond(json("{\"id\":1,\"result\":\"muito\"}"));

    assertThatThrownBy(() -> indexer.read(WalletNetwork.ETHEREUM, ADDRESS))
        .isInstanceOfSatisfying(
            IndexerUnavailableException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(IndexerUnavailableException.Reason.INVALID_RESPONSE));
  }

  @Test
  void anRpcErrorIsUnavailableAndLogsOnlyTheMethod() {
    server
        .expect(requestTo(URL))
        .andRespond(json("{\"id\":1,\"error\":{\"code\":429,\"message\":\"" + KEY + "\"}}"));

    assertThatThrownBy(() -> indexer.read(WalletNetwork.ETHEREUM, ADDRESS))
        .isInstanceOfSatisfying(
            IndexerUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(IndexerUnavailableException.Reason.UNAVAILABLE));
    assertNothingSensitiveWasLogged();
  }

  @Test
  void withoutAKeyItDoesNotCallAnything() {
    AlchemyWalletIndexer keyless = new AlchemyWalletIndexer("", Map.of());

    assertThat(keyless.isConfigured()).isFalse();
    assertThatThrownBy(() -> keyless.read(WalletNetwork.ETHEREUM, ADDRESS))
        .isInstanceOfSatisfying(
            IndexerUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(IndexerUnavailableException.Reason.NO_KEY));
  }

  @Test
  void thePropertiesNeverPrintTheKey() {
    WalletsProperties properties = new WalletsProperties(KEY, null, null, null, false, null, null);

    assertThat(properties.toString()).doesNotContain(KEY);
  }

  private void assertNothingSensitiveWasLogged() {
    for (ILoggingEvent event : logs.list) {
      assertThat(event.getFormattedMessage()).doesNotContain(KEY).doesNotContain(ADDRESS);
      assertThat(event.getThrowableProxy()).isNull();
    }
  }

  private static org.springframework.test.web.client.ResponseCreator json(String body) {
    return withSuccess(body, MediaType.APPLICATION_JSON);
  }
}
