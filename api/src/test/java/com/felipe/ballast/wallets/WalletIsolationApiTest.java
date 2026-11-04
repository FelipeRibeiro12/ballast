package com.felipe.ballast.wallets;

import static com.felipe.ballast.crypto.CryptoApi.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.FakeWalletIndexer;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.crypto.CryptoApi;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Usuário A pedindo recurso de B: as rotas de carteira e o patrimônio que o resumo toca.
@IntegrationTest
class WalletIsolationApiTest {

  private static final Instant NOW = Instant.parse("2026-11-04T15:00:00Z");
  private static final String ADDRESS_B = "0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359";
  private static final java.time.OffsetDateTime DOLLAR_MOMENT =
      java.time.OffsetDateTime.parse("2026-11-04T13:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  @Autowired FakeWalletIndexer indexer;
  @Autowired JdbcClient jdbc;

  private CryptoApi api;

  @BeforeEach
  void setUp() {
    clock.freeze(NOW);
    api = new CryptoApi(mvc);
    // Com dólar, o zero do patrimônio de Alice vem da consulta e não da falta de cotação.
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
  void theOtherUsersWalletIsNotFoundOnAnyWalletRouteAndIsNeverChanged() throws Exception {
    CryptoApi.Actor bob = api.newActor();
    CryptoApi.Actor alice = api.newActor();
    String bobWallet = createWallet(bob, bob.cryptoId(), ADDRESS_B);
    defi(bob, bobWallet, 777700L);

    assertThat(api.send(alice.session(), get("/carteiras/" + bobWallet), null).getStatus())
        .isEqualTo(404);
    assertThat(
            api.send(alice.session(), post("/carteiras/" + bobWallet + "/sincronizar"), null)
                .getStatus())
        .isEqualTo(404);
    assertThat(api.send(alice.session(), delete("/carteiras/" + bobWallet), null).getStatus())
        .isEqualTo(404);

    JsonNode aliceList = expect(api.send(alice.session(), get("/carteiras"), null), 200);
    assertThat(aliceList).isEmpty();
    JsonNode aliceSummary = expect(api.send(alice.session(), get("/carteiras/resumo"), null), 200);
    assertThat(aliceSummary.get("carteiras")).isEmpty();
    assertThat(aliceSummary.get("total").get("valorUsdCentavos").asLong()).isZero();
    JsonNode aliceToday = expect(api.send(alice.session(), get("/patrimonio"), null), 200);
    assertThat(aliceToday.get("defiUsdCentavos").asLong()).isZero();

    JsonNode bobList = expect(api.send(bob.session(), get("/carteiras"), null), 200);
    assertThat(bobList).hasSize(1);
    assertThat(bobList.get(0).get("id").asString()).isEqualTo(bobWallet);
    JsonNode bobSummary = expect(api.send(bob.session(), get("/carteiras/resumo"), null), 200);
    assertThat(bobSummary.get("total").get("valorUsdCentavos").asLong()).isEqualTo(777700L);
  }

  @Test
  void aWalletCannotBeCreatedOnTheOtherUsersAccount() throws Exception {
    CryptoApi.Actor bob = api.newActor();
    CryptoApi.Actor alice = api.newActor();

    Map<String, Object> body =
        Map.of("contaId", bob.cryptoId(), "rede", "ethereum", "endereco", ADDRESS_B);
    assertThat(api.send(alice.session(), post("/carteiras"), body).getStatus()).isEqualTo(400);

    JsonNode bobList = expect(api.send(bob.session(), get("/carteiras"), null), 200);
    assertThat(bobList).isEmpty();
  }

  private String createWallet(CryptoApi.Actor actor, String accountId, String address)
      throws Exception {
    Map<String, Object> body =
        Map.of("contaId", accountId, "rede", "ethereum", "endereco", address);
    return expect(api.send(actor.session(), post("/carteiras"), body), 201).get("id").asString();
  }

  private void defi(CryptoApi.Actor actor, String walletId, long value) {
    jdbc.sql(
            """
            INSERT INTO posicao_carteira
              (id, usuario_id, carteira_id, tipo, categoria, nome, valor_usd_centavos)
            VALUES (:id, :user, :wallet, 'defi', 'deposito', 'posicao', :value)
            """)
        .param("id", UUID.randomUUID())
        .param("user", actor.user().id())
        .param("wallet", UUID.fromString(walletId))
        .param("value", value)
        .update();
  }
}
