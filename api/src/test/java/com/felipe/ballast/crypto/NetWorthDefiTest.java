package com.felipe.ballast.crypto;

import static com.felipe.ballast.FakeWalletIndexer.nativeHolding;
import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.FakeWalletIndexer;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.wallets.WalletNetwork;
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

// DeFi no patrimônio e poeira de saldo sincronizado (ADR-082).
@IntegrationTest
class NetWorthDefiTest {

  private static final String ADDRESS = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";
  private static final Instant NOW = Instant.parse("2026-11-04T15:00:00Z");
  private static final OffsetDateTime DOLLAR_MOMENT = OffsetDateTime.parse("2026-11-04T14:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  @Autowired FakeWalletIndexer indexer;
  @Autowired JdbcClient jdbc;

  private CryptoApi api;
  private CryptoApi.Actor actor;
  private String walletId;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(NOW);
    // Dólar a 5,00 (USDC/BRL) até o fim do dia de hoje.
    jdbc.sql("INSERT INTO cotacao_usdc_brl (momento, preco) VALUES (:m, 5.00)")
        .param("m", DOLLAR_MOMENT)
        .update();
    api = new CryptoApi(mvc);
    actor = api.newActor();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaId", actor.cryptoId());
    body.put("rede", "ethereum");
    body.put("endereco", ADDRESS);
    walletId =
        CryptoApi.expect(api.send(actor.session(), post("/carteiras"), body), 201)
            .get("id")
            .asString();
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    indexer.reset();
    jdbc.sql("DELETE FROM cotacao_usdc_brl WHERE momento = :m").param("m", DOLLAR_MOMENT).update();
  }

  private void defi(String kind, String category, Long value) {
    jdbc.sql(
            """
            INSERT INTO posicao_carteira
              (id, usuario_id, carteira_id, tipo, categoria, nome, valor_usd_centavos)
            VALUES (:id, :user, :wallet, :kind, :category, 'posicao', :value)
            """)
        .param("id", UUID.randomUUID())
        .param("user", actor.user().id())
        .param("wallet", UUID.fromString(walletId))
        .param("kind", kind)
        .param("category", category)
        .param("value", value)
        .update();
  }

  private JsonNode netWorth(String query) throws Exception {
    return CryptoApi.expect(api.send(actor.session(), get("/patrimonio" + query), null), 200);
  }

  @Test
  void todayAddsDefiWithLoansDustAndNftLeftOut() throws Exception {
    defi("defi", "deposito", 5000L);
    defi("defi", "emprestimo", -3000L);
    defi("defi", "deposito", 49L);
    defi("defi", "deposito", null);
    defi("nft", "nft", 99999L);

    JsonNode today = netWorth("");

    assertThat(today.get("defiUsdCentavos").asLong()).isEqualTo(2000L);
    assertThat(today.get("defiCentavos").asLong()).isEqualTo(10000L);
    assertThat(today.get("totalCentavos").asLong())
        .isEqualTo(
            today.get("contasCentavos").asLong()
                + today.get("criptoCentavos").asLong()
                + today.get("rendaFixaCentavos").asLong()
                + 10000L);
  }

  @Test
  void otherDatesHaveZeroDefiAndItStaysOutOfTheTotal() throws Exception {
    defi("defi", "deposito", 5000L);

    JsonNode yesterday = netWorth("?data=2026-11-03");

    assertThat(yesterday.get("defiCentavos").asLong()).isZero();
    assertThat(yesterday.get("defiUsdCentavos").asLong()).isZero();
    assertThat(yesterday.get("totalCentavos").asLong())
        .isEqualTo(
            yesterday.get("contasCentavos").asLong()
                + yesterday.get("criptoCentavos").asLong()
                + yesterday.get("rendaFixaCentavos").asLong());
  }

  @Test
  void theMonthlyHistoryNeverCarriesDefi() throws Exception {
    defi("defi", "deposito", 5000L);

    JsonNode months =
        CryptoApi.expect(api.send(actor.session(), get("/patrimonio/mensal"), null), 200)
            .get("meses");

    JsonNode last = months.get(months.size() - 1);
    assertThat(last.get("totalCentavos").asLong())
        .isEqualTo(
            last.get("contasCentavos").asLong()
                + last.get("criptoCentavos").asLong()
                + last.get("rendaFixaCentavos").asLong());
  }

  @Test
  void anotherUsersDefiNeverEntersTheNetWorth() throws Exception {
    defi("defi", "deposito", 5000L);
    CryptoApi.Actor other = api.newActor();

    JsonNode theirs = CryptoApi.expect(api.send(other.session(), get("/patrimonio"), null), 200);

    assertThat(theirs.get("defiCentavos").asLong()).isZero();
    assertThat(theirs.get("defiUsdCentavos").asLong()).isZero();
  }

  @Test
  void syncedDustLeavesTheNetWorthButTypedDustOfTheSameSizeStays() throws Exception {
    // 1 ETH sincronizado a US$ 0,49 é poeira (fica de fora). 1 BTC digitado a US$ 0,49 fica.
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1"));
    CryptoApi.expect(
        api.send(actor.session(), post("/carteiras/" + walletId + "/sincronizar"), null), 200);
    String ethId = CryptoApi.body(api.listAssets(actor.session())).get(0).get("id").asString();
    api.quoteOk(actor.session(), ethId, "0.49", "2026-11-04T14:00:00Z");
    String bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2026-11-04", 49));
    api.quoteOk(actor.session(), bitcoin, "0.49", "2026-11-04T14:00:00Z");

    JsonNode today = netWorth("");

    // Só o BTC digitado: 0,49 dólar a 5,00 = R$ 2,45.
    assertThat(today.get("criptoCentavos").asLong()).isEqualTo(245L);
    assertThat(today.get("criptoUsdCentavos").asLong()).isEqualTo(49L);
  }

  @Test
  void syncedFiftyCentsStaysInTheNetWorth() throws Exception {
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1"));
    CryptoApi.expect(
        api.send(actor.session(), post("/carteiras/" + walletId + "/sincronizar"), null), 200);
    String ethId = CryptoApi.body(api.listAssets(actor.session())).get(0).get("id").asString();
    api.quoteOk(actor.session(), ethId, "0.50", "2026-11-04T14:00:00Z");

    JsonNode today = netWorth("");

    assertThat(today.get("criptoUsdCentavos").asLong()).isEqualTo(50L);
    assertThat(today.get("criptoCentavos").asLong()).isEqualTo(250L);
  }
}
