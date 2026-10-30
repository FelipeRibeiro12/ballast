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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// O motivo da última leitura que falhou, como a interface precisa para dizer ao dono o que
// consertar.
@IntegrationTest
class WalletReadStatusTest {

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
  void eachFailureReasonComesBackAsAStableMotivo() throws Exception {
    Map<IndexerUnavailableException.Reason, String> expected =
        Map.of(
            IndexerUnavailableException.Reason.NO_KEY, "INDEXADOR_NAO_CONFIGURADO",
            IndexerUnavailableException.Reason.UNAVAILABLE, "INDEXADOR_FORA_DO_AR",
            IndexerUnavailableException.Reason.REJECTED, "INDEXADOR_RECUSOU_A_CHAVE",
            IndexerUnavailableException.Reason.RATE_LIMITED, "INDEXADOR_COTA_ESGOTADA",
            IndexerUnavailableException.Reason.INVALID_RESPONSE, "RESPOSTA_INVALIDA");
    String id = register(ADDRESS);

    for (var entry : expected.entrySet()) {
      clock.advance(Duration.ofSeconds(61));
      indexer.failWith(entry.getKey());

      JsonNode synced = expect(sync(actor, id), 200);

      assertThat(synced.get("leituraFalhou").asBoolean()).isTrue();
      assertThat(synced.get("motivo").asString()).isEqualTo(entry.getValue());
      assertThat(synced.get("ultimaTentativaEm").asString()).isEqualTo(clock.instant().toString());
      // O GET e a lista repetem o motivo gravado.
      assertThat(
              expect(api.send(actor.session(), get("/carteiras/" + id), null), 200)
                  .get("motivo")
                  .asString())
          .isEqualTo(entry.getValue());
      assertThat(
              expect(api.send(actor.session(), get("/carteiras"), null), 200)
                  .get(0)
                  .get("motivo")
                  .asString())
          .isEqualTo(entry.getValue());
    }
  }

  @Test
  void aReadThatWorksHasNoMotivoAndClearsTheOldOne() throws Exception {
    String id = register(ADDRESS);
    indexer.failWith(IndexerUnavailableException.Reason.UNAVAILABLE);
    expect(sync(actor, id), 200);
    clock.advance(Duration.ofSeconds(61));
    indexer.recover();
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1"));

    JsonNode synced = expect(sync(actor, id), 200);

    assertThat(synced.get("leituraFalhou").asBoolean()).isFalse();
    assertThat(synced.get("motivo").isNull()).isTrue();
    assertThat(synced.get("ultimaTentativaEm").asString())
        .isEqualTo(NOW.plusSeconds(61).toString());
  }

  @Test
  void aWalletNeverReadHasNoMotivoNorAttempt() throws Exception {
    String id = register(ADDRESS);

    JsonNode wallet = expect(api.send(actor.session(), get("/carteiras/" + id), null), 200);

    assertThat(wallet.get("motivo").isNull()).isTrue();
    assertThat(wallet.get("ultimaTentativaEm").isNull()).isTrue();
    assertThat(wallet.get("proximaLeituraEm").isNull()).isTrue();
  }

  @Test
  void theSixtySecondLimitIsAMotivoOnlyWhenNothingWorseHappened() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1"));
    expect(sync(actor, id), 200);
    clock.advance(Duration.ofSeconds(10));

    JsonNode throttled = expect(sync(actor, id), 200);

    assertThat(throttled.get("motivo").asString()).isEqualTo("LIMITE_DE_60S");
    assertThat(throttled.get("leituraFalhou").asBoolean()).isFalse();
    assertThat(throttled.get("proximaLeituraEm").asString())
        .isEqualTo(NOW.plusSeconds(60).toString());
    assertThat(indexer.calls()).isEqualTo(1);
  }

  @Test
  void theSixtySecondLimitDoesNotHideTheLastFailure() throws Exception {
    String id = register(ADDRESS);
    indexer.failWith(IndexerUnavailableException.Reason.REJECTED);
    expect(sync(actor, id), 200);
    clock.advance(Duration.ofSeconds(5));

    JsonNode throttled = expect(sync(actor, id), 200);

    assertThat(throttled.get("motivo").asString()).isEqualTo("INDEXADOR_RECUSOU_A_CHAVE");
    assertThat(throttled.get("proximaLeituraEm").asString())
        .isEqualTo(NOW.plusSeconds(60).toString());
  }

  @Test
  void theWalletSaysWhetherTheIndexerIsConfiguredAndTheAutomaticSyncIsOn() throws Exception {
    String id = register(ADDRESS);

    JsonNode configured = expect(api.send(actor.session(), get("/carteiras/" + id), null), 200);
    assertThat(configured.get("indexadorConfigurado").asBoolean()).isTrue();
    // job-enabled fica falso em todos os testes.
    assertThat(configured.get("sincronizacaoAutomatica").asBoolean()).isFalse();

    indexer.setConfigured(false);
    JsonNode missing = expect(api.send(actor.session(), get("/carteiras"), null), 200).get(0);
    assertThat(missing.get("indexadorConfigurado").asBoolean()).isFalse();
    assertThat(missing.get("sincronizacaoAutomatica").asBoolean()).isFalse();
  }

  @Test
  void anotherUsersWalletIsNotFound() throws Exception {
    String id = register(ADDRESS);
    CryptoApi.Actor intruder = api.newActor();

    assertThat(sync(intruder, id).getStatus()).isEqualTo(404);
    assertThat(api.send(intruder.session(), get("/carteiras/" + id), null).getStatus())
        .isEqualTo(404);
    assertThat(indexer.calls()).isZero();
  }

  private String register(String address) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaId", actor.cryptoId());
    body.put("rede", "ethereum");
    body.put("endereco", address);
    return expect(api.send(actor.session(), post("/carteiras"), body), 201).get("id").asString();
  }

  private MockHttpServletResponse sync(CryptoApi.Actor who, String id) throws Exception {
    return api.send(who.session(), post("/carteiras/" + id + "/sincronizar"), null);
  }
}
