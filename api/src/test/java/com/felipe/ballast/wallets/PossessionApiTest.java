package com.felipe.ballast.wallets;

import static com.felipe.ballast.crypto.CryptoApi.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.crypto.CryptoApi;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class PossessionApiTest {

  private static final Instant NOW = Instant.parse("2026-10-29T15:00:00Z");
  private static final String DOMAIN = "ballast.exemplo.test";

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  @Autowired JdbcClient jdbc;

  private CryptoApi api;
  private CryptoApi.Actor actor;
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger rootLogger;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(NOW);
    api = new CryptoApi(mvc);
    actor = api.newActor();
    rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    logs.start();
    rootLogger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logs);
    logs.stop();
    clock.reset();
  }

  @Test
  void issuesAChallengeWithDomainNetworkAndNonceInTheMessage() throws Exception {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();
    String walletId = register("ethereum", keys.address());

    JsonNode challenge = expect(challenge(actor, walletId), 200);

    String nonce = challenge.get("nonce").asString();
    String message = challenge.get("mensagem").asString();
    assertThat(nonce).matches("[0-9a-f]{32}");
    assertThat(message)
        .startsWith(DOMAIN + " wants you to sign in with your Ethereum account:\n")
        .contains("Chain ID: 1\n")
        .contains("Nonce: " + nonce + "\n")
        .contains("URI: https://" + DOMAIN + "\n")
        .contains("Version: 1\n")
        .contains("Issued At: 2026-10-29T15:00:00Z\n")
        .contains("Expiration Time: 2026-10-29T15:05:00Z")
        .contains("Isto não é uma transação e não custa nada.");
    // O endereço da mensagem é o EIP-55, que as carteiras mostram.
    assertThat(message.split("\n")[1])
        .isEqualToIgnoringCase(keys.address())
        .isEqualTo("0x" + WalletAddress.checksum(keys.address().substring(2)));
    assertThat(Instant.parse(challenge.get("expiraEm").asString()))
        .isEqualTo(NOW.plus(Duration.ofMinutes(5)));
  }

  @Test
  void usesTheChainIdOfBaseAndTheConfiguredDomainNotTheHostHeader() throws Exception {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();
    String walletId = register("base", keys.address());

    MockHttpServletResponse response =
        mvc.perform(
                post("/carteiras/" + walletId + "/posse/desafio")
                    .cookie(actor.session())
                    .header("Host", "atacante.example"))
            .andReturn()
            .getResponse();

    String message = expect(response, 200).get("mensagem").asString();
    assertThat(message).contains("Chain ID: 8453\n").contains(DOMAIN).doesNotContain("atacante");
  }

  @Test
  void aValidEvmSignatureFillsVerifiedAt() throws Exception {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();
    String walletId = register("ethereum", keys.address());
    assertThat(
            expect(send(actor, get("/carteiras/" + walletId), null), 200)
                .get("verificadaEm")
                .isNull())
        .isTrue();
    JsonNode challenge = expect(challenge(actor, walletId), 200);

    JsonNode verified =
        expect(
            verify(
                actor,
                walletId,
                challenge.get("nonce").asString(),
                keys.sign(challenge.get("mensagem").asString())),
            200);

    assertThat(verified.get("id").asString()).isEqualTo(walletId);
    assertThat(Instant.parse(verified.get("verificadaEm").asString())).isEqualTo(NOW);
    JsonNode read = expect(send(actor, get("/carteiras/" + walletId), null), 200);
    assertThat(Instant.parse(read.get("verificadaEm").asString())).isEqualTo(NOW);
    assertThat(count("posse_desafio", walletId)).isZero();
  }

  @Test
  void aSignatureFromAnotherAddressIsRejectedAndBurnsTheNonce() throws Exception {
    TestWalletKeys.Evm owner = new TestWalletKeys.Evm();
    TestWalletKeys.Evm stranger = new TestWalletKeys.Evm();
    String walletId = register("ethereum", owner.address());
    JsonNode challenge = expect(challenge(actor, walletId), 200);
    String nonce = challenge.get("nonce").asString();
    String message = challenge.get("mensagem").asString();

    MockHttpServletResponse wrong = verify(actor, walletId, nonce, stranger.sign(message));

    assertThat(wrong.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.body(wrong).get("erro").asString()).isEqualTo("assinatura inválida");
    assertThat(verifiedAt(walletId)).isNull();
    // A assinatura certa chega tarde: o nonce já foi consumido.
    MockHttpServletResponse late = verify(actor, walletId, nonce, owner.sign(message));
    assertThat(late.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.body(late).get("erro").asString()).isEqualTo("desafio expirado");
    assertThat(verifiedAt(walletId)).isNull();
  }

  @Test
  void aReusedNonceIsRejected() throws Exception {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();
    String walletId = register("ethereum", keys.address());
    JsonNode challenge = expect(challenge(actor, walletId), 200);
    String nonce = challenge.get("nonce").asString();
    String signature = keys.sign(challenge.get("mensagem").asString());
    expect(verify(actor, walletId, nonce, signature), 200);

    MockHttpServletResponse again = verify(actor, walletId, nonce, signature);

    assertThat(again.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.body(again).get("erro").asString()).isEqualTo("desafio expirado");
  }

  @Test
  void anExpiredNonceIsRejectedEvenWithAGoodSignature() throws Exception {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();
    String walletId = register("ethereum", keys.address());
    JsonNode challenge = expect(challenge(actor, walletId), 200);
    clock.freeze(NOW.plus(Duration.ofMinutes(5)).plusSeconds(1));

    MockHttpServletResponse late =
        verify(
            actor,
            walletId,
            challenge.get("nonce").asString(),
            keys.sign(challenge.get("mensagem").asString()));

    assertThat(late.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.body(late).get("erro").asString()).isEqualTo("desafio expirado");
    assertThat(verifiedAt(walletId)).isNull();
    assertThat(count("posse_desafio", walletId)).isZero();
  }

  @Test
  void aWrongNonceIsRejectedAndTheRightOneNoLongerWorks() throws Exception {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();
    String walletId = register("ethereum", keys.address());
    JsonNode challenge = expect(challenge(actor, walletId), 200);
    String nonce = challenge.get("nonce").asString();
    String signature = keys.sign(challenge.get("mensagem").asString());
    String other = nonce.substring(0, 31) + (nonce.endsWith("0") ? "1" : "0");

    MockHttpServletResponse wrong = verify(actor, walletId, other, signature);

    assertThat(wrong.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.body(wrong).get("erro").asString()).isEqualTo("assinatura inválida");
    assertThat(verify(actor, walletId, nonce, signature).getStatus()).isEqualTo(400);
    assertThat(verifiedAt(walletId)).isNull();
  }

  @Test
  void verifyingWithoutAChallengeIsRejected() throws Exception {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();
    String walletId = register("ethereum", keys.address());

    MockHttpServletResponse response = verify(actor, walletId, "0".repeat(32), keys.sign("x"));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.body(response).get("erro").asString()).isEqualTo("desafio expirado");
  }

  @Test
  void anotherChallengeReplacesTheFirstOne() throws Exception {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();
    String walletId = register("ethereum", keys.address());
    JsonNode first = expect(challenge(actor, walletId), 200);
    clock.freeze(NOW.plusSeconds(6));
    JsonNode second = expect(challenge(actor, walletId), 200);

    assertThat(count("posse_desafio", walletId)).isEqualTo(1);
    assertThat(second.get("nonce").asString()).isNotEqualTo(first.get("nonce").asString());
    MockHttpServletResponse old =
        verify(
            actor,
            walletId,
            first.get("nonce").asString(),
            keys.sign(first.get("mensagem").asString()));
    assertThat(old.getStatus()).isEqualTo(400);
  }

  @Test
  void aSecondChallengeWithinFiveSecondsGets429AndKeepsTheFirst() throws Exception {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();
    String walletId = register("ethereum", keys.address());
    JsonNode first = expect(challenge(actor, walletId), 200);
    clock.freeze(NOW.plusSeconds(4));

    MockHttpServletResponse tooSoon = challenge(actor, walletId);

    assertThat(tooSoon.getStatus()).isEqualTo(429);
    assertThat(CryptoApi.body(tooSoon).get("erro").asString())
        .isEqualTo("muitas tentativas, tente mais tarde");
    // O desafio anterior segue valendo.
    JsonNode verified =
        expect(
            verify(
                actor,
                walletId,
                first.get("nonce").asString(),
                keys.sign(first.get("mensagem").asString())),
            200);
    assertThat(verified.get("verificadaEm").isNull()).isFalse();
  }

  @Test
  void aValidSolanaSignatureFillsVerifiedAt() throws Exception {
    TestWalletKeys.Solana keys = new TestWalletKeys.Solana();
    String walletId = register("solana", keys.address());
    JsonNode challenge = expect(challenge(actor, walletId), 200);
    String message = challenge.get("mensagem").asString();
    assertThat(message)
        .contains(DOMAIN)
        .contains("Rede: solana\n")
        .contains("Nonce: " + challenge.get("nonce").asString() + "\n")
        .contains(keys.address());

    JsonNode verified =
        expect(verify(actor, walletId, challenge.get("nonce").asString(), keys.sign(message)), 200);

    assertThat(Instant.parse(verified.get("verificadaEm").asString())).isEqualTo(NOW);
  }

  @Test
  void anInvalidSolanaSignatureIsRejected() throws Exception {
    TestWalletKeys.Solana owner = new TestWalletKeys.Solana();
    TestWalletKeys.Solana stranger = new TestWalletKeys.Solana();
    String walletId = register("solana", owner.address());
    JsonNode challenge = expect(challenge(actor, walletId), 200);

    MockHttpServletResponse response =
        verify(
            actor,
            walletId,
            challenge.get("nonce").asString(),
            stranger.sign(challenge.get("mensagem").asString()));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.body(response).get("erro").asString()).isEqualTo("assinatura inválida");
    assertThat(verifiedAt(walletId)).isNull();
  }

  @Test
  void anotherUserGetsNotFoundOnBothRoutes() throws Exception {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();
    String walletId = register("ethereum", keys.address());
    JsonNode challenge = expect(challenge(actor, walletId), 200);
    CryptoApi.Actor intruder = api.newActor();
    String signature = keys.sign(challenge.get("mensagem").asString());
    String nonce = challenge.get("nonce").asString();

    assertThat(challenge(intruder, walletId).getStatus()).isEqualTo(404);
    assertThat(verify(intruder, walletId, nonce, signature).getStatus()).isEqualTo(404);
    // O pedido alheio não consumiu o desafio do dono.
    assertThat(count("posse_desafio", walletId)).isEqualTo(1);
    for (String id : new String[] {UUID.randomUUID().toString(), "não-é-uuid"}) {
      assertThat(challenge(actor, id).getStatus()).isEqualTo(404);
      assertThat(verify(actor, id, nonce, signature).getStatus()).isEqualTo(404);
    }
    expect(verify(actor, walletId, nonce, signature), 200);
  }

  @Test
  void requiresASession() throws Exception {
    String id = UUID.randomUUID().toString();

    assertThat(api.send(null, post("/carteiras/" + id + "/posse/desafio"), null).getStatus())
        .isEqualTo(401);
    assertThat(
            api.send(
                    null,
                    post("/carteiras/" + id + "/posse/verificar"),
                    Map.of("nonce", "a", "assinatura", "b"))
                .getStatus())
        .isEqualTo(401);
  }

  @Test
  void rejectsUnknownFieldsMissingFieldsAndHugeSignatures() throws Exception {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();
    String walletId = register("ethereum", keys.address());
    expect(challenge(actor, walletId), 200);

    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("nonce", "0".repeat(32));
    extra.put("assinatura", "0x00");
    extra.put("mensagem", "outra");
    assertThat(send(actor, post("/carteiras/" + walletId + "/posse/verificar"), extra).getStatus())
        .isEqualTo(400);
    assertThat(
            send(
                    actor,
                    post("/carteiras/" + walletId + "/posse/verificar"),
                    Map.of("nonce", "0".repeat(32)))
                .getStatus())
        .isEqualTo(400);
    assertThat(verify(actor, walletId, "0".repeat(32), "0x" + "a".repeat(500)).getStatus())
        .isEqualTo(400);
    assertThat(verify(actor, walletId, "0".repeat(1000), "0x00").getStatus()).isEqualTo(400);
  }

  @Test
  void neverLogsAddressSignatureMessageOrNonce() throws Exception {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();
    TestWalletKeys.Evm stranger = new TestWalletKeys.Evm();
    String walletId = register("ethereum", keys.address());
    JsonNode challenge = expect(challenge(actor, walletId), 200);
    String nonce = challenge.get("nonce").asString();
    String badSignature = stranger.sign(challenge.get("mensagem").asString());
    verify(actor, walletId, nonce, badSignature);
    JsonNode second = refreshChallenge(walletId);
    verify(
        actor,
        walletId,
        second.get("nonce").asString(),
        keys.sign(second.get("mensagem").asString()));

    for (ILoggingEvent event : logs.list) {
      String line = event.getFormattedMessage().toLowerCase();
      assertThat(line)
          .doesNotContain(keys.address().substring(2))
          .doesNotContain(badSignature.substring(2, 40))
          .doesNotContain(nonce)
          .doesNotContain(second.get("nonce").asString())
          .doesNotContain("wants you to sign in");
    }
    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .anyMatch(line -> line.contains("posse") && line.contains(walletId));
  }

  private JsonNode refreshChallenge(String walletId) throws Exception {
    clock.advance(Duration.ofSeconds(6));
    return expect(challenge(actor, walletId), 200);
  }

  private String register(String network, String address) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaId", actor.cryptoId());
    body.put("rede", network);
    body.put("endereco", address);
    return expect(send(actor, post("/carteiras"), body), 201).get("id").asString();
  }

  private MockHttpServletResponse challenge(CryptoApi.Actor who, String walletId) throws Exception {
    return api.send(who.session(), post("/carteiras/" + walletId + "/posse/desafio"), null);
  }

  private MockHttpServletResponse verify(
      CryptoApi.Actor who, String walletId, String nonce, String signature) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("nonce", nonce);
    body.put("assinatura", signature);
    return send(who, post("/carteiras/" + walletId + "/posse/verificar"), body);
  }

  private MockHttpServletResponse send(
      CryptoApi.Actor who,
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      Object body)
      throws Exception {
    return api.send(who.session(), request, body);
  }

  private Instant verifiedAt(String walletId) {
    OffsetDateTime value =
        jdbc.sql("SELECT verificada_em FROM carteira_observada WHERE id = :id")
            .param("id", UUID.fromString(walletId))
            .query((rs, row) -> rs.getObject("verificada_em", OffsetDateTime.class))
            .list()
            .get(0);
    return value == null ? null : value.toInstant();
  }

  private int count(String table, String walletId) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE carteira_id = :id")
        .param("id", UUID.fromString(walletId))
        .query(Integer.class)
        .single();
  }
}
