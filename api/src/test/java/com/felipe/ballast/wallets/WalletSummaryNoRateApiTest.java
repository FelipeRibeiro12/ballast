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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Sem nenhuma cotação do dólar (USDC/BRL nem PTAX), o campo em real vai nulo e o dólar segue.
// Os dólares das linhas gravadas são apagados no teste e devolvidos no fim, porque a tabela é
// compartilhada entre as classes de integração.
@IntegrationTest
class WalletSummaryNoRateApiTest {

  private static final Instant NOW = Instant.parse("2026-11-04T15:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  @Autowired FakeWalletIndexer indexer;
  @Autowired JdbcClient jdbc;

  private CryptoApi api;

  @BeforeEach
  void setUp() {
    clock.freeze(NOW);
    api = new CryptoApi(mvc);
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    indexer.reset();
  }

  @Test
  void withoutAnyDollarTheRealFieldsAreNullAndTheDollarFieldsStay() throws Exception {
    List<Map<String, Object>> usdc =
        jdbc.sql("SELECT momento, preco, fonte FROM cotacao_usdc_brl").query().listOfRows();
    List<Map<String, Object>> ptax =
        jdbc.sql("SELECT serie, data, valor FROM indice WHERE serie = 'dolar'")
            .query()
            .listOfRows();
    jdbc.sql("DELETE FROM cotacao_usdc_brl").update();
    jdbc.sql("DELETE FROM indice WHERE serie = 'dolar'").update();
    try {
      CryptoApi.Actor actor = api.newActor();
      String wallet = syncedWallet(actor);
      api.quoteOk(actor.session(), firstAssetId(actor), "100", "2026-11-04T14:00:00Z");
      api.quoteOk(actor.session(), firstAssetId(actor), "80", "2026-11-04T14:30:00Z");
      defi(actor, wallet, 5000L, 200L);

      JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);
      JsonNode found = summary.get("carteiras").get(0);
      assertThat(found.get("valorUsdCentavos").asLong()).isEqualTo(13000L);
      assertThat(found.get("valorBrlCentavos").isNull()).isTrue();
      assertThat(found.get("variacao24hBrlCentavos").isNull()).isTrue();
      assertThat(summary.get("total").get("valorBrlCentavos").isNull()).isTrue();
      assertThat(summary.get("total").get("variacao24hBrlCentavos").isNull()).isTrue();

      // O contrato diz: sem dólar, o real fica nulo ou zero no patrimônio; o dólar do DeFi segue.
      JsonNode today = expect(api.send(actor.session(), get("/patrimonio"), null), 200);
      assertThat(today.get("defiUsdCentavos").isNull()).isFalse();
      assertThat(today.get("defiUsdCentavos").asLong()).isEqualTo(5000L);
      assertThat(today.get("defiCentavos").isNull() || today.get("defiCentavos").asLong() == 0)
          .isTrue();
    } finally {
      for (Map<String, Object> row : usdc) {
        jdbc.sql("INSERT INTO cotacao_usdc_brl (momento, preco, fonte) VALUES (:m, :p, :f)")
            .param("m", row.get("momento"))
            .param("p", row.get("preco"))
            .param("f", row.get("fonte"))
            .update();
      }
      for (Map<String, Object> row : ptax) {
        jdbc.sql("INSERT INTO indice (serie, data, valor) VALUES ('dolar', :d, :v)")
            .param("d", row.get("data"))
            .param("v", row.get("valor"))
            .update();
      }
    }
  }

  private String syncedWallet(CryptoApi.Actor actor) throws Exception {
    String address = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";
    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("contaId", actor.cryptoId());
    body.put("rede", "ethereum");
    body.put("endereco", address);
    String id =
        expect(api.send(actor.session(), post("/carteiras"), body), 201).get("id").asString();
    indexer.publish(WalletNetwork.ETHEREUM, address, nativeHolding("1"));
    expect(api.send(actor.session(), post("/carteiras/" + id + "/sincronizar"), null), 200);
    return id;
  }

  private String firstAssetId(CryptoApi.Actor actor) throws Exception {
    return expect(api.listAssets(actor.session()), 200).get(0).get("id").asString();
  }

  private void defi(CryptoApi.Actor actor, String walletId, long value, long change) {
    jdbc.sql(
            """
            INSERT INTO posicao_carteira
              (id, usuario_id, carteira_id, tipo, categoria, nome, valor_usd_centavos,
               variacao_24h_usd_centavos)
            VALUES (:id, :user, :wallet, 'defi', 'deposito', 'posicao', :value, :change)
            """)
        .param("id", UUID.randomUUID())
        .param("user", actor.user().id())
        .param("wallet", UUID.fromString(walletId))
        .param("value", value)
        .param("change", change)
        .update();
  }
}
