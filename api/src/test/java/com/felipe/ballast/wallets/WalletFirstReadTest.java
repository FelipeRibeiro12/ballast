package com.felipe.ballast.wallets;

import static com.felipe.ballast.FakeWalletIndexer.nativeHolding;
import static com.felipe.ballast.crypto.CryptoApi.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.FakeWalletIndexer;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.crypto.CryptoApi;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// A primeira leitura roda depois do commit do cadastro e fora da requisição: o POST responde 201
// sem esperar a rede, e a leitura aparece no GET seguinte.
@IntegrationTest
@TestPropertySource(properties = "ballast.carteiras.first-read-enabled=true")
class WalletFirstReadTest {

  private static final String ADDRESS = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";
  private static final Instant NOW = Instant.parse("2026-10-30T15:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  @Autowired FakeWalletIndexer indexer;

  private CryptoApi api;
  private CryptoApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(NOW);
    api = new CryptoApi(mvc);
    actor = api.newActor();
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    indexer.reset();
  }

  @Test
  void registeringAWalletReadsItOnceInTheBackground() throws Exception {
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("2"));

    String id = register();

    await().atMost(Duration.ofSeconds(10)).until(() -> indexer.calls() == 1);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              JsonNode wallet = fetch(id);
              assertThat(wallet.get("sincronizadaEm").isNull()).isFalse();
              assertThat(wallet.get("motivo").isNull()).isTrue();
              assertThat(wallet.get("saldos")).hasSize(1);
            });
  }

  @Test
  void theFirstReadCountsForTheSixtySecondLimit() throws Exception {
    String id = register();
    await().atMost(Duration.ofSeconds(10)).until(() -> indexer.calls() == 1);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> !fetch(id).get("ultimaTentativaEm").isNull());

    JsonNode again =
        expect(api.send(actor.session(), post("/carteiras/" + id + "/sincronizar"), null), 200);

    assertThat(again.get("motivo").asString()).isEqualTo("LIMITE_DE_60S");
    assertThat(indexer.calls()).isEqualTo(1);
  }

  @Test
  void aFailedFirstReadDoesNotUndoTheRegistration() throws Exception {
    indexer.failWith(IndexerUnavailableException.Reason.UNAVAILABLE);

    String id = register();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(fetch(id).get("motivo").asString()).isEqualTo("INDEXADOR_FORA_DO_AR"));
    assertThat(expect(api.send(actor.session(), get("/carteiras"), null), 200)).hasSize(1);
  }

  @Test
  void aCrashInsideTheIndexerDoesNotUndoTheRegistrationEither() throws Exception {
    indexer.crashWith(new IllegalStateException("explodiu"));

    String id = register();

    await().atMost(Duration.ofSeconds(10)).until(() -> indexer.calls() == 1);
    assertThat(fetch(id).get("id").asString()).isEqualTo(id);
  }

  @Test
  void withoutAConfiguredIndexerNothingIsCalled() throws Exception {
    indexer.setConfigured(false);

    String id = register();

    // Dá tempo de uma tarefa errada rodar antes de afirmar que nada rodou.
    Thread.sleep(300);
    assertThat(indexer.calls()).isZero();
    JsonNode wallet = fetch(id);
    assertThat(wallet.get("indexadorConfigurado").asBoolean()).isFalse();
    assertThat(wallet.get("ultimaTentativaEm").isNull()).isTrue();
  }

  @Test
  void aRejectedRegistrationDoesNotTriggerAnyRead() throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaId", actor.cryptoId());
    body.put("rede", "ethereum");
    body.put("endereco", "0x123");

    assertThat(api.send(actor.session(), post("/carteiras"), body).getStatus()).isEqualTo(400);

    Thread.sleep(300);
    assertThat(indexer.calls()).isZero();
  }

  @Test
  void anotherUserCannotReadTheWalletByItsId() throws Exception {
    String id = register();
    await().atMost(Duration.ofSeconds(10)).until(() -> indexer.calls() == 1);
    CryptoApi.Actor intruder = api.newActor();

    assertThat(
            api.send(intruder.session(), post("/carteiras/" + id + "/sincronizar"), null)
                .getStatus())
        .isEqualTo(404);
    assertThat(api.send(intruder.session(), get("/carteiras/" + id), null).getStatus())
        .isEqualTo(404);
  }

  private JsonNode fetch(String id) throws Exception {
    return expect(api.send(actor.session(), get("/carteiras/" + id), null), 200);
  }

  private String register() throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaId", actor.cryptoId());
    body.put("rede", "ethereum");
    body.put("endereco", ADDRESS);
    return expect(api.send(actor.session(), post("/carteiras"), body), 201).get("id").asString();
  }
}
