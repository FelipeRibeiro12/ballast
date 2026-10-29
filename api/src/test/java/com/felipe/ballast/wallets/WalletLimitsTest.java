package com.felipe.ballast.wallets;

import static com.felipe.ballast.FakeWalletIndexer.nativeHolding;
import static com.felipe.ballast.FakeWalletIndexer.token;
import static com.felipe.ballast.crypto.CryptoApi.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.FakeWalletIndexer;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.TestcontainersConfiguration;
import com.felipe.ballast.crypto.CryptoApi;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Contexto próprio com tetos baixos, como o UserLimitsTest: os da suíte são generosos.
@Import({TestcontainersConfiguration.class, TestClock.Config.class, FakeWalletIndexer.Config.class})
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.recurrence.job-enabled=false",
      "ballast.indices.job-enabled=false",
      "ballast.cotacoes.job-enabled=false",
      "ballast.carteiras.job-enabled=false",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1",
      "ballast.limits.wallets=2",
      "ballast.limits.crypto-assets=2"
    })
@AutoConfigureMockMvc
class WalletLimitsTest {

  private static final String FIRST = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";
  private static final String SECOND = "0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359";
  private static final String THIRD = "0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb";
  // Contratos públicos do USDC e do USDT, não são segredo.
  private static final String TOKEN_A =
      "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"; // gitleaks:allow
  private static final String TOKEN_B =
      "0xdac17f958d2ee523a2206206994597c13d831ec7"; // gitleaks:allow

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  @Autowired FakeWalletIndexer indexer;

  private CryptoApi api;
  private CryptoApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2026-10-29T15:00:00Z"));
    api = new CryptoApi(mvc);
    actor = api.newActor();
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    indexer.reset();
  }

  @Test
  void theThirdWalletIsRefusedWithConflict() throws Exception {
    expect(create(FIRST), 201);
    expect(create(SECOND), 201);

    assertThat(create(THIRD).getStatus()).isEqualTo(409);
    assertThat(CryptoApi.body(api.send(actor.session(), get("/carteiras"), null))).hasSize(2);
  }

  @Test
  void assetsThatDoNotFitAreLeftOutAndTheReadStillSucceeds() throws Exception {
    String id = expect(create(FIRST), 201).get("id").asString();
    indexer.publish(
        WalletNetwork.ETHEREUM,
        FIRST,
        nativeHolding("1"),
        token(TOKEN_A, "AAA", 18, "5"),
        token(TOKEN_B, "BBB", 18, "7"));

    JsonNode synced =
        expect(api.send(actor.session(), post("/carteiras/" + id + "/sincronizar"), null), 200);

    // Teto de 2 ativos: o ETH e o primeiro token cabem, o terceiro fica de fora sem derrubar a
    // leitura.
    assertThat(synced.get("leituraFalhou").asBoolean()).isFalse();
    assertThat(synced.get("saldos")).hasSize(2);
    assertThat(synced.get("sincronizadaEm").isNull()).isFalse();
  }

  private org.springframework.mock.web.MockHttpServletResponse create(String address)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaId", actor.cryptoId());
    body.put("rede", "ethereum");
    body.put("endereco", address);
    return api.send(actor.session(), post("/carteiras"), body);
  }
}
