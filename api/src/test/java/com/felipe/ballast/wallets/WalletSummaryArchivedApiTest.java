package com.felipe.ballast.wallets;

import static com.felipe.ballast.crypto.CryptoApi.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.crypto.CryptoApi;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Conta cripto arquivada: o DeFi dela sai do patrimônio (NOT c.arquivada). O resumo de carteiras
// tem que sair pela mesma regra, senão a tela de carteiras e a de patrimônio dizem coisas
// diferentes.
@IntegrationTest
class WalletSummaryArchivedApiTest {

  private static final Instant NOW = Instant.parse("2026-11-04T15:00:00Z");
  private static final OffsetDateTime DOLLAR_MOMENT = OffsetDateTime.parse("2026-11-04T13:00:00Z");
  private static final String ADDRESS = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
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
    jdbc.sql("DELETE FROM cotacao_usdc_brl WHERE momento = :m").param("m", DOLLAR_MOMENT).update();
  }

  @Test
  void archivedAccountDefiLeavesTheSummaryTotalTheSameWayItLeavesThePatrimonio() throws Exception {
    CryptoApi.Actor actor = api.newActor();
    Map<String, Object> body =
        Map.of("contaId", actor.cryptoId(), "rede", "ethereum", "endereco", ADDRESS);
    String wallet =
        expect(api.send(actor.session(), post("/carteiras"), body), 201).get("id").asString();
    jdbc.sql(
            """
            INSERT INTO posicao_carteira
              (id, usuario_id, carteira_id, tipo, categoria, nome, valor_usd_centavos)
            VALUES (:id, :user, :wallet, 'defi', 'deposito', 'posicao', 5000)
            """)
        .param("id", UUID.randomUUID())
        .param("user", actor.user().id())
        .param("wallet", UUID.fromString(wallet))
        .update();

    JsonNode before = expect(api.send(actor.session(), get("/patrimonio"), null), 200);
    assertThat(before.get("defiUsdCentavos").asLong()).isEqualTo(5000L);

    expect(
        api.send(actor.session(), patch("/contas/" + actor.cryptoId()), Map.of("arquivada", true)),
        200);

    JsonNode after = expect(api.send(actor.session(), get("/patrimonio"), null), 200);
    assertThat(after.get("defiUsdCentavos").asLong()).isZero();
    JsonNode summary = expect(api.send(actor.session(), get("/carteiras/resumo"), null), 200);
    assertThat(summary.get("total").get("valorUsdCentavos").asLong()).isZero();
  }
}
