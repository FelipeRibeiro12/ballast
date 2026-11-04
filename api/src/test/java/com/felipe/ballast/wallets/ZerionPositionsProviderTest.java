package com.felipe.ballast.wallets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ZerionPositionsProviderTest {

  private static final String ADDRESS = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";

  private MockRestServiceServer server;
  private ZerionPositionsProvider provider;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://zerion.test");
    server = MockRestServiceServer.bindTo(builder).build();
    provider = new ZerionPositionsProvider(true, builder.build());
  }

  private static org.springframework.test.web.client.ResponseCreator json(String body) {
    return withSuccess(body, MediaType.APPLICATION_JSON);
  }

  @Test
  void groupsALiquidityPoolIntoOneRowAndKeepsLoansNegative() {
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("/positions/")))
        .andRespond(
            json(
                """
                {"data":[
                 {"attributes":{"position_type":"deposit","protocol_module":"liquidity_pool",
                  "protocol":"Uniswap V2","name":"Asset","group_id":"g1","value":10.005,
                  "quantity":{"numeric":"1.5"},"fungible_info":{"symbol":"USDC"},
                  "flags":{"displayable":true,"is_trash":false}}},
                 {"attributes":{"position_type":"deposit","protocol_module":"liquidity_pool",
                  "protocol":"Uniswap V2","name":"Asset","group_id":"g1","value":5.0,
                  "quantity":{"numeric":"0.002"},"fungible_info":{"symbol":"WETH"},
                  "flags":{"displayable":true,"is_trash":false}}},
                 {"attributes":{"position_type":"staked","protocol_module":"staked",
                  "protocol":"Lido","name":"Staked","value":100.1,
                  "quantity":{"numeric":"0.03"},"fungible_info":{"symbol":"stETH"},
                  "flags":{"displayable":true,"is_trash":false}}},
                 {"attributes":{"position_type":"loan","protocol_module":"lending",
                  "protocol":"Aave V3","name":"Borrowed","value":20,
                  "quantity":{"numeric":"20"},"fungible_info":{"symbol":"DAI"},
                  "flags":{"displayable":true,"is_trash":false}}},
                 {"attributes":{"position_type":"deposit","name":"Lixo","value":9,
                  "fungible_info":{"symbol":"SPAM"},"flags":{"is_trash":true}}}
                ]}"""));
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("/nft-positions/")))
        .andRespond(
            json(
                """
                {"data":[
                 {"attributes":{"amount":"2","price":0.5,
                  "nft_info":{"token_id":"7","name":"Ape #7","interface":"erc721",
                              "flags":{"is_spam":false}},
                  "collection_info":{"name":"Apes"}}},
                 {"attributes":{"amount":"1","price":9,
                  "nft_info":{"token_id":"1","name":"Spam","flags":{"is_spam":true}}}}
                ]}"""));

    Optional<List<PositionsProvider.Position>> read =
        provider.read(WalletNetwork.ETHEREUM, ADDRESS);

    assertThat(read).isPresent();
    List<PositionsProvider.Position> all = read.get();
    assertThat(all).hasSize(4);
    PositionsProvider.Position pool = all.get(0);
    assertThat(pool.category()).isEqualTo("liquidez");
    assertThat(pool.name()).isEqualTo("USDC / WETH");
    assertThat(pool.quantity()).isNull();
    assertThat(pool.valueUsdCents()).isEqualTo(1500L);
    assertThat(all.get(1).category()).isEqualTo("stake");
    assertThat(all.get(1).valueUsdCents()).isEqualTo(10010L);
    assertThat(all.get(2).category()).isEqualTo("emprestimo");
    assertThat(all.get(2).valueUsdCents()).isEqualTo(-2000L);
    PositionsProvider.Position nft = all.get(3);
    assertThat(nft.kind()).isEqualTo("nft");
    assertThat(nft.protocol()).isEqualTo("Apes");
    assertThat(nft.quantity()).isEqualByComparingTo(new BigDecimal("2"));
    assertThat(nft.valueUsdCents()).isEqualTo(100L);
    server.verify();
  }

  @Test
  void keepsOldPositionsWhenZerionFailsAndTreatsUnsupportedNetworkAsEmpty() {
    server
        .expect(ExpectedCount.manyTimes(), requestTo(org.hamcrest.Matchers.anything()))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    assertThat(provider.read(WalletNetwork.BASE, ADDRESS)).isEmpty();
    assertThat(provider.read(WalletNetwork.BITCOIN, "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        .contains(List.of());
  }

  @Test
  void readsTheDailyChangeSumsPoolLegsAndNegatesLoans() {
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("/positions/")))
        .andRespond(
            json(
                """
                {"data":[
                 {"attributes":{"position_type":"deposit","protocol_module":"liquidity_pool",
                  "protocol":"Uniswap V2","name":"Asset","group_id":"g1","value":10.0,
                  "changes":{"absolute_1d":-0.333,"percent_1d":-3.3},
                  "fungible_info":{"symbol":"USDC"},"flags":{"displayable":true}}},
                 {"attributes":{"position_type":"deposit","protocol_module":"liquidity_pool",
                  "protocol":"Uniswap V2","name":"Asset","group_id":"g1","value":5.0,
                  "changes":{"absolute_1d":1.0},
                  "fungible_info":{"symbol":"WETH"},"flags":{"displayable":true}}},
                 {"attributes":{"position_type":"staked","protocol_module":"staked",
                  "protocol":"Lido","name":"Staked","value":100.1,
                  "changes":null,
                  "fungible_info":{"symbol":"stETH"},"flags":{"displayable":true}}},
                 {"attributes":{"position_type":"loan","protocol_module":"lending",
                  "protocol":"Aave V3","name":"Borrowed","value":20,
                  "changes":{"absolute_1d":0.5},
                  "fungible_info":{"symbol":"DAI"},"flags":{"displayable":true}}},
                 {"attributes":{"position_type":"deposit","protocol_module":"deposit",
                  "name":"Enorme","value":1,"changes":{"absolute_1d":99999999999999999},
                  "fungible_info":{"symbol":"BIG"},"flags":{"displayable":true}}}
                ]}"""));
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("/nft-positions/")))
        .andRespond(json("{\"data\":[]}"));

    List<PositionsProvider.Position> all = provider.read(WalletNetwork.ETHEREUM, ADDRESS).get();

    assertThat(all).hasSize(4);
    assertThat(all.get(0).change24hUsdCents()).isEqualTo(67L);
    assertThat(all.get(1).change24hUsdCents()).isNull();
    assertThat(all.get(2).change24hUsdCents()).isEqualTo(-50L);
    assertThat(all.get(3).change24hUsdCents()).isNull();
  }

  @Test
  void readsSolanaAsAChainOfPositions() {
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("chain_ids%5D=solana")))
        .andRespond(
            json(
                """
                {"data":[
                 {"attributes":{"position_type":"staked","protocol_module":"staked",
                  "protocol":"Marinade","name":"Staked","value":42,
                  "fungible_info":{"symbol":"mSOL"},"flags":{"displayable":true}}}
                ]}"""));
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("/nft-positions/")))
        .andRespond(json("{\"data\":[]}"));

    List<PositionsProvider.Position> all =
        provider.read(WalletNetwork.SOLANA, "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM").get();

    assertThat(all).hasSize(1);
    assertThat(all.get(0).valueUsdCents()).isEqualTo(4200L);
    server.verify();
  }
}
