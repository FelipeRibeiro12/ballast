package com.felipe.ballast.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.felipe.ballast.FakeQuoteSource;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// O banco é compartilhado e o job lê os ativos de todo mundo: cada teste dá por feito o histórico
// dos ativos que já existiam e usa um contrato sorteado.
@IntegrationTest
class QuoteBackfillTest {

  private static final Instant NOW = Instant.parse("2026-10-23T15:00:00Z");
  private static final Instant DAY_1 = Instant.parse("2026-10-20T00:00:00Z");
  private static final Instant DAY_2 = Instant.parse("2026-10-21T00:00:00Z");
  private static final Instant DAY_3 = Instant.parse("2026-10-22T00:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  @Autowired FakeQuoteSource source;
  @Autowired QuoteBackfill backfill;
  private CryptoApi api;

  @BeforeEach
  void setUp() {
    api = new CryptoApi(mvc);
    source.reset();
    clock.freeze(NOW);
    jdbc.sql("UPDATE ativo_cripto SET historico_em = now() WHERE historico_em IS NULL").update();
  }

  @AfterEach
  void tearDown() {
    source.reset();
    clock.reset();
  }

  private static String randomContract() {
    byte[] bytes = new byte[20];
    ThreadLocalRandom.current().nextBytes(bytes);
    return "0x" + HexFormat.of().formatHex(bytes);
  }

  private static Map<String, Object> token(String contract) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rede", "ethereum");
    body.put("contrato", contract);
    body.put("simbolo", "TKN");
    body.put("decimais", 18);
    return body;
  }

  private static AssetRef ref(String contract) {
    return new AssetRef(CryptoNetwork.ETHEREUM, contract);
  }

  private static ProviderQuote at(Instant moment, String price) {
    return new ProviderQuote(new BigDecimal(price), moment);
  }

  private record Row(String fonte, Instant momento) {}

  private List<Row> quotesOf(String assetId) {
    return jdbc.sql(
            "SELECT fonte, momento FROM cotacao WHERE ativo_id = :id::uuid ORDER BY momento")
        .param("id", assetId)
        .query(
            (rs, row) ->
                new Row(
                    rs.getString("fonte"),
                    rs.getObject("momento", OffsetDateTime.class).toInstant()))
        .list();
  }

  private List<FakeQuoteSource.HistoryRequest> requestsFor(String contract) {
    return source.historyRequests().stream().filter(r -> r.asset().equals(ref(contract))).toList();
  }

  @Test
  void fillsOnlyTheDaysBeforeTheFirstQuoteOfEachUserAndAsksOnce() throws Exception {
    String contract = randomContract();
    CryptoApi.Actor ana = api.newActor();
    CryptoApi.Actor bia = api.newActor();
    String deAna = api.createAssetOk(ana.session(), token(contract));
    String deBia = api.createAssetOk(bia.session(), token(contract));
    Instant typed = Instant.parse("2026-10-21T12:00:00Z");
    api.quoteOk(ana.session(), deAna, "9", typed.toString());
    source.publishHistory(ref(contract), List.of(at(DAY_1, "1"), at(DAY_2, "2"), at(DAY_3, "3")));

    backfill.backfill();
    backfill.backfill();

    // A cotação digitada continua valendo dali para frente: o histórico só enche o que vem antes.
    assertThat(quotesOf(deAna))
        .containsExactly(
            new Row("coingecko", DAY_1), new Row("coingecko", DAY_2), new Row("manual", typed));
    assertThat(quotesOf(deBia))
        .containsExactly(
            new Row("coingecko", DAY_1), new Row("coingecko", DAY_2), new Row("coingecko", DAY_3));
    // Um pedido para os dois donos, e a segunda rodada não pede de novo.
    assertThat(requestsFor(contract))
        .containsExactly(
            new FakeQuoteSource.HistoryRequest(
                ref(contract), NOW.minus(Duration.ofDays(364)), NOW));
  }

  @Test
  void thePositionAtAPastDateUsesTheFilledQuote() throws Exception {
    String contract = randomContract();
    CryptoApi.Actor ana = api.newActor();
    String asset = api.createAssetOk(ana.session(), token(contract));
    api.moveOk(
        ana.session(), CryptoApi.movement(ana.cryptoId(), asset, "compra", "2", "2026-10-20", 500));
    source.publishHistory(ref(contract), List.of(at(DAY_1, "1.5"), at(DAY_2, "2.5")));

    backfill.backfill();

    // O ponto das 00:00 UTC do dia 21 é 21h do dia 20 em São Paulo: a última cotação do dia 20.
    JsonNode positions = CryptoApi.expect(api.positions(ana.session(), "?data=2026-10-20"), 200);
    JsonNode quote = positions.get("posicoes").get(0).get("cotacao");
    assertThat(quote.get("precoUsd").asString()).isEqualTo("2.5");
    assertThat(quote.get("fonte").asString()).isEqualTo("coingecko");
    assertThat(Instant.parse(quote.get("momento").asString())).isEqualTo(DAY_2);
  }

  @Test
  void providerDownLeavesTheAssetForTheNextRound() throws Exception {
    String contract = randomContract();
    CryptoApi.Actor ana = api.newActor();
    String asset = api.createAssetOk(ana.session(), token(contract));
    source.publishHistory(ref(contract), List.of(at(DAY_1, "1")));
    source.goDown();

    assertThatCode(backfill::backfill).doesNotThrowAnyException();
    assertThat(quotesOf(asset)).isEmpty();

    source.reset();
    source.publishHistory(ref(contract), List.of(at(DAY_1, "1")));
    backfill.backfill();

    assertThat(quotesOf(asset)).containsExactly(new Row("coingecko", DAY_1));
  }

  @Test
  void anAssetTheProviderDoesNotKnowIsAskedOnlyOnce() throws Exception {
    String contract = randomContract();
    CryptoApi.Actor ana = api.newActor();
    String asset = api.createAssetOk(ana.session(), token(contract));

    backfill.backfill();
    backfill.backfill();

    assertThat(requestsFor(contract)).hasSize(1);
    assertThat(quotesOf(asset)).isEmpty();
  }

  @Test
  void skipsUntrustedAssetsAndImplausiblePrices() throws Exception {
    String untrusted = randomContract();
    String contract = randomContract();
    CryptoApi.Actor ana = api.newActor();
    String untrustedId = api.createAssetOk(ana.session(), token(untrusted));
    jdbc.sql("UPDATE ativo_cripto SET confiavel = false WHERE id = :id::uuid")
        .param("id", untrustedId)
        .update();
    String asset = api.createAssetOk(ana.session(), token(contract));
    source.publishHistory(ref(untrusted), List.of(at(DAY_1, "1")));
    // Arredonda para zero em oito casas e não cabe em numeric(20,8): os dois ficam de fora.
    source.publishHistory(
        ref(contract),
        List.of(at(DAY_1, "0.000000004"), at(DAY_2, "1000000000000"), at(DAY_3, "3")));

    backfill.backfill();

    assertThat(requestsFor(untrusted)).isEmpty();
    assertThat(quotesOf(untrustedId)).isEmpty();
    assertThat(quotesOf(asset)).containsExactly(new Row("coingecko", DAY_3));
  }

  @Test
  void asksAtMostTenAssetsPerRound() throws Exception {
    CryptoApi.Actor ana = api.newActor();
    List<String> contracts = new ArrayList<>();
    for (int i = 0; i < 11; i++) {
      String contract = randomContract();
      contracts.add(contract);
      api.createAssetOk(ana.session(), token(contract));
    }

    backfill.backfill();
    assertThat(source.historyRequests()).hasSize(10);

    backfill.backfill();
    assertThat(source.historyRequests()).hasSize(11);
    assertThat(source.historyRequests())
        .extracting(FakeQuoteSource.HistoryRequest::asset)
        .containsExactlyInAnyOrderElementsOf(
            contracts.stream().map(QuoteBackfillTest::ref).toList());
  }
}
