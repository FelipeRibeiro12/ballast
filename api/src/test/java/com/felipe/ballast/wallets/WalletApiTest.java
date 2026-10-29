package com.felipe.ballast.wallets;

import static com.felipe.ballast.FakeWalletIndexer.nativeHolding;
import static com.felipe.ballast.FakeWalletIndexer.token;
import static com.felipe.ballast.crypto.CryptoApi.body;
import static com.felipe.ballast.crypto.CryptoApi.expect;
import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.felipe.ballast.FakeWalletIndexer;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.crypto.CryptoApi;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
class WalletApiTest {

  private static final String ADDRESS = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";
  private static final String ADDRESS_MIXED_CASE = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";
  private static final String SOLANA_ADDRESS = "11111111111111111111111111111111";
  private static final String SOLANA_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
  private static final String SPAM = "0xdead00000000000000000000000000000000beef";
  private static final String USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";
  private static final Instant NOW = Instant.parse("2026-10-29T15:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
  private static final LocalDate DOLLAR_DAY = LocalDate.of(2025, 1, 2);

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  @Autowired FakeWalletIndexer indexer;
  @Autowired JdbcClient jdbc;

  private CryptoApi api;
  private CryptoApi.Actor actor;
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger rootLogger;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(NOW);
    // PTAX 1,00 desde antes de tudo, como no NetWorthTest: reais igual a dólar.
    jdbc.sql(
            "INSERT INTO indice (serie, data, valor) VALUES ('dolar', :d, 1) ON CONFLICT DO NOTHING")
        .param("d", DOLLAR_DAY)
        .update();
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
    indexer.reset();
    jdbc.sql("DELETE FROM indice WHERE serie = 'dolar' AND data = :d")
        .param("d", DOLLAR_DAY)
        .update();
  }

  // ---- cadastro

  @Test
  void registersAnAddressInLowerCaseWithoutReadingTheNetwork() throws Exception {
    Map<String, Object> request = wallet(actor.cryptoId(), "ethereum", ADDRESS_MIXED_CASE);
    request.put("rotulo", "  Cofre  ");

    JsonNode created = expect(send(actor, post("/carteiras"), request), 201);

    assertThat(created.get("id").asString()).isNotBlank();
    assertThat(created.get("contaId").asString()).isEqualTo(actor.cryptoId());
    assertThat(created.get("rede").asString()).isEqualTo("ethereum");
    assertThat(created.get("endereco").asString()).isEqualTo(ADDRESS);
    assertThat(created.get("rotulo").asString()).isEqualTo("Cofre");
    assertThat(created.get("sincronizadaEm").isNull()).isTrue();
    assertThat(created.get("leituraFalhou").asBoolean()).isFalse();
    assertThat(created.get("saldos")).isEmpty();
    assertThat(indexer.calls()).isZero();
  }

  @Test
  void rejectsAddressesThatDoNotFitTheNetwork() throws Exception {
    String[] invalid = {
      // checksum EIP-55 errado (uma letra trocada de caixa)
      "0x5AAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
      // tamanho
      "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beae",
      // 31 bytes em base58 não é endereço de Solana nem de EVM
      "1111111111111111111111111111111",
      "",
    };
    for (String address : invalid) {
      MockHttpServletResponse response =
          send(actor, post("/carteiras"), wallet(actor.cryptoId(), "ethereum", address));
      assertThat(response.getStatus()).as("ethereum " + address.length()).isEqualTo(400);
    }
    assertThat(
            send(
                    actor,
                    post("/carteiras"),
                    wallet(actor.cryptoId(), "solana", "1111111111111111111111111111111"))
                .getStatus())
        .isEqualTo(400);
    assertThat(
            send(actor, post("/carteiras"), wallet(actor.cryptoId(), "solana", ADDRESS))
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void rejectsANetworkThatDoesNotExistAndUnknownFields() throws Exception {
    assertThat(
            send(actor, post("/carteiras"), wallet(actor.cryptoId(), "bitcoin", ADDRESS))
                .getStatus())
        .isEqualTo(400);
    Map<String, Object> extra = wallet(actor.cryptoId(), "ethereum", ADDRESS);
    extra.put("usuarioId", actor.user().id().toString());
    assertThat(send(actor, post("/carteiras"), extra).getStatus()).isEqualTo(400);
  }

  @Test
  void validationErrorsDoNotEchoTheAddress() throws Exception {
    MockHttpServletResponse response =
        send(
            actor, post("/carteiras"), wallet(actor.cryptoId(), "ethereum", "0x" + "z".repeat(40)));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(response)).doesNotContain("zzzz");
  }

  @Test
  void rejectsADuplicateEvenWithAnotherCase() throws Exception {
    expect(send(actor, post("/carteiras"), wallet(actor.cryptoId(), "ethereum", ADDRESS)), 201);

    assertThat(
            send(
                    actor,
                    post("/carteiras"),
                    wallet(actor.cryptoId(), "ethereum", ADDRESS_MIXED_CASE))
                .getStatus())
        .isEqualTo(409);
    // A mesma sequência em outra rede é outro endereço.
    expect(send(actor, post("/carteiras"), wallet(actor.cryptoId(), "base", ADDRESS)), 201);
  }

  @Test
  void rejectsAccountsThatAreNotTheUsersCryptoAccount() throws Exception {
    CryptoApi.Actor other = api.newActor();

    assertThat(
            send(actor, post("/carteiras"), wallet(other.cryptoId(), "ethereum", ADDRESS))
                .getStatus())
        .isEqualTo(400);
    assertThat(
            send(actor, post("/carteiras"), wallet(actor.checkingId(), "ethereum", ADDRESS))
                .getStatus())
        .isEqualTo(400);
    assertThat(
            send(actor, post("/carteiras"), wallet(CryptoApi.randomId(), "ethereum", ADDRESS))
                .getStatus())
        .isEqualTo(400);
    assertThat(
            send(actor, post("/carteiras"), wallet("nao-e-uuid", "ethereum", ADDRESS)).getStatus())
        .isEqualTo(400);
    assertThat(send(actor, get("/carteiras"), null).getContentAsString()).isEqualTo("[]");
  }

  @Test
  void requiresASession() throws Exception {
    assertThat(
            api.send(null, post("/carteiras"), wallet(actor.cryptoId(), "ethereum", ADDRESS))
                .getStatus())
        .isEqualTo(401);
    assertThat(api.send(null, get("/carteiras"), null).getStatus()).isEqualTo(401);
  }

  // ---- leitura

  @Test
  void syncWritesTheBalanceAsEntriesWithoutCost() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(
        WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1.5"), token(USDC, "USDC", 6, "250.25"));

    JsonNode synced = expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);

    assertThat(synced.get("leituraFalhou").asBoolean()).isFalse();
    assertThat(Instant.parse(synced.get("sincronizadaEm").asString())).isEqualTo(NOW);
    JsonNode eth = balance(synced, null);
    assertThat(eth.get("simbolo").asString()).isEqualTo("ETH");
    assertThat(eth.get("quantidade").asString()).isEqualTo("1.5");
    assertThat(eth.get("confiavel").asBoolean()).isTrue();
    assertThat(eth.get("custoPendente").asBoolean()).isTrue();
    JsonNode usdc = balance(synced, USDC);
    assertThat(usdc.get("quantidade").asString()).isEqualTo("250.25");
    assertThat(usdc.get("confiavel").asBoolean()).isFalse();

    JsonNode movements = expect(api.listMovements(actor.session(), ""), 200);
    assertThat(movements).hasSize(2);
    for (JsonNode movement : movements) {
      assertThat(movement.get("tipo").asString()).isEqualTo("entrada");
      assertThat(movement.get("origem").asString()).isEqualTo("sincronizada");
      assertThat(movement.get("data").asString()).isEqualTo(TODAY.toString());
      assertThat(movement.get("custoUsdCentavos").isNull()).isTrue();
      assertThat(movement.get("custoCentavos").isNull()).isTrue();
    }
  }

  @Test
  void dateOfTheEntryIsTheSaoPauloDayNotTheUtcDay() throws Exception {
    // 01:30 UTC de 30/10 ainda é 29/10 em São Paulo.
    clock.freeze(Instant.parse("2026-10-30T01:30:00Z"));
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1"));

    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);

    assertThat(expect(api.listMovements(actor.session(), ""), 200).get(0).get("data").asString())
        .isEqualTo("2026-10-29");
  }

  @Test
  void syncTwiceWritesNothingTheSecondTime() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1.5"));
    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    clock.advance(Duration.ofSeconds(61));

    JsonNode second = expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);

    assertThat(indexer.calls()).isEqualTo(2);
    assertThat(expect(api.listMovements(actor.session(), ""), 200)).hasSize(1);
    assertThat(balance(second, null).get("quantidade").asString()).isEqualTo("1.5");
    assertThat(Instant.parse(second.get("sincronizadaEm").asString()))
        .isEqualTo(NOW.plusSeconds(61));
  }

  @Test
  void aBalanceThatDropsWritesAnExitAndAZeroBalanceDisappears() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(
        WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1.5"), token(USDC, "USDC", 6, "10"));
    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    clock.advance(Duration.ofSeconds(61));
    // O USDC some da leitura e o ETH cai para 1.
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1"));

    JsonNode synced = expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);

    assertThat(synced.get("saldos")).hasSize(1);
    assertThat(balance(synced, null).get("quantidade").asString()).isEqualTo("1");
    List<String> types = new ArrayList<>();
    for (JsonNode movement : expect(api.listMovements(actor.session(), ""), 200)) {
      types.add(movement.get("tipo").asString());
    }
    assertThat(types).containsExactlyInAnyOrder("entrada", "entrada", "saida", "saida");
  }

  @Test
  void aFailedReadKeepsTheOldBalanceAndTheDateAndSaysSo() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1.5"));
    JsonNode first = expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    clock.advance(Duration.ofSeconds(61));
    indexer.failWith(IndexerUnavailableException.Reason.UNAVAILABLE);

    JsonNode failed = expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);

    assertThat(failed.get("leituraFalhou").asBoolean()).isTrue();
    assertThat(failed.get("sincronizadaEm")).isEqualTo(first.get("sincronizadaEm"));
    assertThat(balance(failed, null).get("quantidade").asString()).isEqualTo("1.5");
    assertThat(
            jdbc.sql("SELECT erro_ultima_sync FROM carteira_observada WHERE id = :id")
                .param("id", java.util.UUID.fromString(id))
                .query(String.class)
                .single())
        .isEqualTo("indexador_indisponivel");
    // GET repete o mesmo estado, e a leitura seguinte que dá certo limpa o aviso.
    assertThat(
            expect(send(actor, get("/carteiras/" + id), null), 200)
                .get("leituraFalhou")
                .asBoolean())
        .isTrue();
    clock.advance(Duration.ofSeconds(61));
    indexer.recover();
    assertThat(
            expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200)
                .get("leituraFalhou")
                .asBoolean())
        .isFalse();
  }

  @Test
  void aFailedReadWritesNothing() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1.5"));
    indexer.failWith(IndexerUnavailableException.Reason.INVALID_RESPONSE);

    JsonNode failed = expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);

    assertThat(failed.get("leituraFalhou").asBoolean()).isTrue();
    assertThat(failed.get("sincronizadaEm").isNull()).isTrue();
    assertThat(failed.get("saldos")).isEmpty();
    assertThat(expect(api.listMovements(actor.session(), ""), 200)).isEmpty();
    assertThat(
            jdbc.sql("SELECT erro_ultima_sync FROM carteira_observada WHERE id = :id")
                .param("id", java.util.UUID.fromString(id))
                .query(String.class)
                .single())
        .isEqualTo("resposta_invalida");
  }

  @Test
  void anIndexerWithoutAKeyIsAFailedReadNotAnError() throws Exception {
    String id = register(ADDRESS);
    indexer.failWith(IndexerUnavailableException.Reason.NO_KEY);

    JsonNode failed = expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);

    assertThat(failed.get("leituraFalhou").asBoolean()).isTrue();
  }

  @Test
  void aSecondReadWithinSixtySecondsDoesNotCallTheIndexer() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1"));
    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("9"));
    clock.advance(Duration.ofSeconds(59));

    JsonNode again = expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);

    assertThat(indexer.calls()).isEqualTo(1);
    assertThat(balance(again, null).get("quantidade").asString()).isEqualTo("1");
    clock.advance(Duration.ofSeconds(1));
    JsonNode later = expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    assertThat(indexer.calls()).isEqualTo(2);
    assertThat(balance(later, null).get("quantidade").asString()).isEqualTo("9");
  }

  @Test
  void aFailedReadAlsoStartsTheSixtySecondsWindow() throws Exception {
    String id = register(ADDRESS);
    indexer.failWith(IndexerUnavailableException.Reason.UNAVAILABLE);
    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);

    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);

    assertThat(indexer.calls()).isEqualTo(1);
  }

  @Test
  void solanaSymbolIsTheStartOfTheMintAndHostileSymbolsAreCleaned() throws Exception {
    String solana = registerOn("solana", SOLANA_ADDRESS);
    indexer.publish(
        WalletNetwork.SOLANA,
        SOLANA_ADDRESS,
        new FakeWalletIndexer.Holding(null, "SOL", 9, new java.math.BigDecimal("2")),
        token(SOLANA_MINT, "IGNORADO", 6, "5"));
    JsonNode synced = expect(send(actor, post("/carteiras/" + solana + "/sincronizar"), null), 200);

    assertThat(balance(synced, SOLANA_MINT).get("simbolo").asString()).isEqualTo("EPjFWdd5");
    assertThat(balance(synced, null).get("simbolo").asString()).isEqualTo("SOL");

    String evm = register(ADDRESS);
    indexer.publish(
        WalletNetwork.ETHEREUM,
        ADDRESS,
        token(SPAM, "  ‮GOLD​   \n  TOKEN " + "x".repeat(60), 18, "1"));
    JsonNode hostile = expect(send(actor, post("/carteiras/" + evm + "/sincronizar"), null), 200);
    String symbol = balance(hostile, SPAM).get("simbolo").asString();
    assertThat(symbol).startsWith("GOLD TOKEN x").doesNotContain("‮", "​", "\n");
    assertThat(symbol.length()).isLessThanOrEqualTo(20);
  }

  @Test
  void invalidHoldingsFromTheIndexerAreIgnoredAndTheRestIsRead() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(
        WalletNetwork.ETHEREUM,
        ADDRESS,
        nativeHolding("1"),
        token("não é contrato", "X", 18, "1"),
        token(USDC, "USDC", 6, "1.1234567"));

    JsonNode synced = expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);

    assertThat(synced.get("saldos")).hasSize(1);
    assertThat(synced.get("leituraFalhou").asBoolean()).isFalse();
  }

  // ---- spam e patrimônio

  @Test
  void anUnknownTokenStaysOutOfTheNetWorthUntilThePersonPromotesIt() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(
        WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("2"), token(SPAM, "SCAM", 18, "1000000"));
    JsonNode synced = expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    String ethId = balance(synced, null).get("ativoId").asString();
    String spamId = balance(synced, SPAM).get("ativoId").asString();
    api.quoteOk(actor.session(), ethId, "2000", "2026-10-29T12:00:00Z");
    api.quoteOk(actor.session(), spamId, "1", "2026-10-29T12:00:00Z");

    JsonNode before = expect(api.netWorth(actor.session(), "?data=2026-10-29"), 200);

    // 2 ETH a 2000 dólares, PTAX 1: o lixo de 1 milhão a 1 dólar não entra, nem em semCotacao.
    assertThat(before.get("criptoCentavos").asLong()).isEqualTo(400_000);
    assertThat(before.get("semCotacao")).isEmpty();
    JsonNode positions = expect(api.positions(actor.session(), "?data=2026-10-29"), 200);
    JsonNode spamPosition = position(positions, spamId);
    assertThat(spamPosition.get("confiavel").asBoolean()).isFalse();
    assertThat(position(positions, ethId).get("confiavel").asBoolean()).isTrue();

    Map<String, Object> promote = new LinkedHashMap<>();
    promote.put("simbolo", "SCAM");
    promote.put("decimais", 18);
    promote.put("confiavel", true);
    JsonNode promoted = expect(api.updateAsset(actor.session(), spamId, promote), 200);
    assertThat(promoted.get("confiavel").asBoolean()).isTrue();

    JsonNode after = expect(api.netWorth(actor.session(), "?data=2026-10-29"), 200);
    assertThat(after.get("criptoCentavos").asLong()).isEqualTo(400_000 + 100_000_000);
  }

  @Test
  void anUnknownTokenWithoutQuoteIsNotListedAsUnquoted() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, token(SPAM, "SCAM", 18, "5"));
    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);

    JsonNode netWorth = expect(api.netWorth(actor.session(), "?data=2026-10-29"), 200);

    assertThat(netWorth.get("semCotacao")).isEmpty();
    assertThat(netWorth.get("criptoCentavos").asLong()).isZero();
  }

  // ---- custo pendente

  @Test
  void pendingCostNullsTheAverageUntilThePersonInformsIt() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("2"));
    JsonNode synced = expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    String ethId = balance(synced, null).get("ativoId").asString();
    api.quoteOk(actor.session(), ethId, "2000", "2026-10-29T12:00:00Z");

    JsonNode pending =
        position(expect(api.positions(actor.session(), "?data=2026-10-29"), 200), ethId);
    assertThat(pending.get("custoPendente").asBoolean()).isTrue();
    assertThat(pending.get("custoMedioUsd").isNull()).isTrue();
    assertThat(pending.get("custoDaPosicaoUsdCentavos").isNull()).isTrue();
    assertThat(pending.get("ganhoUsdCentavos").isNull()).isTrue();
    assertThat(pending.get("valorUsdCentavos").asLong()).isEqualTo(400_000);
    JsonNode netWorth = expect(api.netWorth(actor.session(), "?data=2026-10-29"), 200);
    assertThat(netWorth.get("criptoCentavos").asLong()).isEqualTo(400_000);
    assertThat(netWorth.get("criptoAportadoCentavos").isNull()).isTrue();
    assertThat(netWorth.get("criptoValorizacaoCentavos").isNull()).isTrue();

    String movementId =
        expect(api.listMovements(actor.session(), ""), 200).get(0).get("id").asString();
    JsonNode saved =
        expect(send(actor, put("/movimentos-cripto/" + movementId + "/custo"), cost(300_000)), 200);
    assertThat(saved.get("custoUsdCentavos").asLong()).isEqualTo(300_000);
    assertThat(saved.get("custoCentavos").asLong()).isEqualTo(300_000);

    JsonNode known =
        position(expect(api.positions(actor.session(), "?data=2026-10-29"), 200), ethId);
    assertThat(known.get("custoPendente").asBoolean()).isFalse();
    assertThat(known.get("custoMedioUsd").asString()).startsWith("1500");
    assertThat(known.get("custoDaPosicaoUsdCentavos").asLong()).isEqualTo(300_000);
    assertThat(known.get("ganhoUsdCentavos").asLong()).isEqualTo(100_000);
    JsonNode after = expect(api.netWorth(actor.session(), "?data=2026-10-29"), 200);
    assertThat(after.get("criptoAportadoCentavos").asLong()).isEqualTo(300_000);
    assertThat(after.get("criptoValorizacaoCentavos").asLong()).isEqualTo(100_000);
  }

  @Test
  void theCostCanBeSetOnlyOnceAndOnlyOnASyncedMovement() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("2"));
    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    String movementId =
        expect(api.listMovements(actor.session(), ""), 200).get(0).get("id").asString();
    expect(send(actor, put("/movimentos-cripto/" + movementId + "/custo"), cost(100)), 200);

    assertThat(
            send(actor, put("/movimentos-cripto/" + movementId + "/custo"), cost(200)).getStatus())
        .isEqualTo(409);

    String bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
    String manual =
        api.moveOk(
            actor.session(),
            movement(actor.cryptoId(), bitcoin, "compra", "1", "2026-10-01", 5000));
    assertThat(send(actor, put("/movimentos-cripto/" + manual + "/custo"), cost(200)).getStatus())
        .isEqualTo(400);
    assertThat(send(actor, put("/movimentos-cripto/" + movementId + "/custo"), cost(0)).getStatus())
        .isEqualTo(400);
    assertThat(
            send(
                    actor,
                    put("/movimentos-cripto/" + movementId + "/custo"),
                    "{\"custoUsdCentavos\":5,\"x\":1}")
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void aSyncedMovementIsNotDeletedByHandAndTypedEntriesAreRefused() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("2"));
    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    String movementId =
        expect(api.listMovements(actor.session(), ""), 200).get(0).get("id").asString();

    assertThat(api.removeMovement(actor.session(), movementId).getStatus()).isEqualTo(409);
    assertThat(expect(api.listMovements(actor.session(), ""), 200)).hasSize(1);

    String bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
    for (String type : List.of("entrada", "saida")) {
      assertThat(
              api.move(
                      actor.session(),
                      movement(actor.cryptoId(), bitcoin, type, "1", "2026-10-01", 5000))
                  .getStatus())
          .isEqualTo(400);
    }
  }

  // ---- apagar

  @Test
  void deletingTheWalletTakesItsMovementsAndKeepsTheTypedOnes() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("2"));
    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    String bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2026-10-01", 5000));

    assertThat(send(actor, delete("/carteiras/" + id), null).getStatus()).isEqualTo(204);

    JsonNode left = expect(api.listMovements(actor.session(), ""), 200);
    assertThat(left).hasSize(1);
    assertThat(left.get(0).get("origem").asString()).isEqualTo("manual");
    assertThat(send(actor, get("/carteiras/" + id), null).getStatus()).isEqualTo(404);
  }

  @Test
  void deletingACryptoAccountWithAWalletWorks() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("2"));
    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);

    MockHttpServletResponse response =
        api.send(actor.session(), delete("/contas/" + actor.cryptoId()), null);

    assertThat(response.getStatus()).as(CryptoApi.text(response)).isIn(200, 204);
    assertThat(send(actor, get("/carteiras"), null).getContentAsString()).isEqualTo("[]");
    assertThat(
            jdbc.sql("SELECT count(*) FROM carteira_observada WHERE usuario_id = :u")
                .param("u", actor.user().id())
                .query(Long.class)
                .single())
        .isZero();
  }

  // ---- outro usuário

  @Test
  void anotherUserGetsNotFoundOnEveryRouteThatTakesAnId() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("2"));
    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    String movementId =
        expect(api.listMovements(actor.session(), ""), 200).get(0).get("id").asString();
    CryptoApi.Actor intruder = api.newActor();

    assertThat(send(intruder, get("/carteiras/" + id), null).getStatus()).isEqualTo(404);
    assertThat(send(intruder, delete("/carteiras/" + id), null).getStatus()).isEqualTo(404);
    assertThat(send(intruder, post("/carteiras/" + id + "/sincronizar"), null).getStatus())
        .isEqualTo(404);
    assertThat(
            send(intruder, put("/movimentos-cripto/" + movementId + "/custo"), cost(100))
                .getStatus())
        .isEqualTo(404);
    assertThat(send(intruder, get("/carteiras"), null).getContentAsString()).isEqualTo("[]");
    // Nada do que o intruso tentou mexeu no dono, e o indexador só foi chamado pela leitura do
    // dono.
    assertThat(indexer.calls()).isEqualTo(1);
    assertThat(send(actor, get("/carteiras/" + id), null).getStatus()).isEqualTo(200);
    JsonNode movement = expect(api.listMovements(actor.session(), ""), 200).get(0);
    assertThat(movement.get("custoUsdCentavos").isNull()).isTrue();
    // Mesma resposta para id inexistente e para id que não é UUID.
    assertThat(send(intruder, get("/carteiras/" + CryptoApi.randomId()), null).getStatus())
        .isEqualTo(404);
    assertThat(send(intruder, get("/carteiras/abc"), null).getStatus()).isEqualTo(404);
  }

  @Test
  void theSameAddressCanBeObservedByTwoUsers() throws Exception {
    register(ADDRESS);
    CryptoApi.Actor other = api.newActor();

    expect(send(other, post("/carteiras"), wallet(other.cryptoId(), "ethereum", ADDRESS)), 201);
  }

  // ---- logs

  @Test
  void theAddressNeverReachesTheLogs() throws Exception {
    // Cadastro, leitura boa, leitura que falha, apagar e um cadastro recusado.
    String id = register(ADDRESS);
    send(actor, post("/carteiras"), wallet(actor.cryptoId(), "ethereum", ADDRESS));
    send(actor, post("/carteiras"), wallet(actor.cryptoId(), "ethereum", "0x" + "g".repeat(40)));
    indexer.publish(
        WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1"), token(SPAM, "SPAM", 18, "1"));
    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    clock.advance(Duration.ofSeconds(61));
    indexer.failWith(IndexerUnavailableException.Reason.UNAVAILABLE);
    expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    send(actor, delete("/carteiras/" + id), null);

    assertThat(logs.list).isNotEmpty();
    for (ILoggingEvent event : logs.list) {
      String text =
          (event.getFormattedMessage() + " " + String.valueOf(event.getThrowableProxy()))
              .toLowerCase(java.util.Locale.ROOT);
      assertThat(text).doesNotContain(ADDRESS.substring(2)).doesNotContain("gggggggg");
      assertThat(text).doesNotContain(SPAM.substring(2));
    }
    assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.ERROR)).isEmpty();
  }

  // ---- contrato

  @Test
  void responseFieldsMatchTheOpenApiSchema() throws Exception {
    String id = register(ADDRESS);
    indexer.publish(WalletNetwork.ETHEREUM, ADDRESS, nativeHolding("1"));
    JsonNode synced = expect(send(actor, post("/carteiras/" + id + "/sincronizar"), null), 200);
    JsonNode schemas =
        body(api.send(null, get("/v3/api-docs"), null)).get("components").get("schemas");

    assertThat(fieldNames(synced)).isEqualTo(fieldNames(schemas.get("Carteira").get("properties")));
    assertThat(fieldNames(synced.get("saldos").get(0)))
        .isEqualTo(fieldNames(schemas.get("SaldoCarteira").get("properties")));
    assertThat(requiredNames(schemas.get("Carteira")))
        .isEqualTo(fieldNames(schemas.get("Carteira").get("properties")));
    JsonNode movement = expect(api.listMovements(actor.session(), ""), 200).get(0);
    assertThat(fieldNames(movement))
        .isEqualTo(fieldNames(schemas.get("MovimentoCripto").get("properties")));
    String ethId = movement.get("ativoId").asString();
    api.quoteOk(actor.session(), ethId, "10", "2026-10-29T12:00:00Z");
    JsonNode position =
        expect(api.positions(actor.session(), "?data=2026-10-29"), 200).get("posicoes").get(0);
    JsonNode positionProperties = schemas.get("PosicaoCripto").get("properties");
    for (String field : fieldNames(position)) {
      assertThat(positionProperties.has(field)).as(field).isTrue();
    }
    assertThat(positionProperties.has("confiavel")).isTrue();
    assertThat(positionProperties.has("custoPendente")).isTrue();
  }

  // ---- ajudas

  private String register(String address) throws Exception {
    return registerOn("ethereum", address);
  }

  private String registerOn(String network, String address) throws Exception {
    return expect(send(actor, post("/carteiras"), wallet(actor.cryptoId(), network, address)), 201)
        .get("id")
        .asString();
  }

  private static Map<String, Object> wallet(String accountId, String network, String address) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaId", accountId);
    body.put("rede", network);
    body.put("endereco", address);
    return body;
  }

  private static Map<String, Object> cost(long cents) {
    return Map.of("custoUsdCentavos", cents);
  }

  private MockHttpServletResponse send(
      CryptoApi.Actor who,
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      Object requestBody)
      throws Exception {
    return api.send(who.session(), request, requestBody);
  }

  private static JsonNode balance(JsonNode wallet, String contract) {
    for (JsonNode balance : wallet.get("saldos")) {
      JsonNode value = balance.get("contrato");
      if (contract == null ? value.isNull() : contract.equals(value.asString())) {
        return balance;
      }
    }
    throw new IllegalStateException("saldo não encontrado: " + contract);
  }

  private static JsonNode position(JsonNode positions, String assetId) {
    for (JsonNode position : positions.get("posicoes")) {
      if (position.get("ativoId").asString().equals(assetId)) {
        return position;
      }
    }
    throw new IllegalStateException("posição não encontrada");
  }

  private static java.util.Set<String> fieldNames(JsonNode node) {
    java.util.Set<String> names = new java.util.TreeSet<>();
    node.propertyNames().forEach(names::add);
    return names;
  }

  private static java.util.Set<String> requiredNames(JsonNode schema) {
    java.util.Set<String> names = new java.util.TreeSet<>();
    schema.get("required").forEach(item -> names.add(item.asString()));
    return names;
  }
}
