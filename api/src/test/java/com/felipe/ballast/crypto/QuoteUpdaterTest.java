package com.felipe.ballast.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.FakeQuoteSource;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// O banco é compartilhado e o job lê os ativos de todo mundo: cada teste usa um contrato sorteado,
// que nenhum outro teste tem.
@IntegrationTest
class QuoteUpdaterTest {

  private static final Instant NOW = Instant.parse("2026-10-23T15:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  @Autowired FakeQuoteSource source;
  @Autowired QuoteUpdater updater;
  private CryptoApi api;

  @BeforeEach
  void setUp() {
    api = new CryptoApi(mvc);
    source.reset();
    clock.freeze(NOW);
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

  private record Row(String fonte, String moeda, BigDecimal preco, Instant momento) {}

  private List<Row> quotesOf(String assetId) {
    return jdbc.sql(
            "SELECT fonte, moeda, preco, momento FROM cotacao WHERE ativo_id = :id::uuid"
                + " ORDER BY momento")
        .param("id", assetId)
        .query(
            (rs, row) ->
                new Row(
                    rs.getString("fonte"),
                    rs.getString("moeda"),
                    rs.getBigDecimal("preco"),
                    rs.getObject("momento", java.time.OffsetDateTime.class).toInstant()))
        .list();
  }

  private void publish(String contract, String price, Instant at) {
    source.publish(
        new AssetRef(CryptoNetwork.ETHEREUM, contract),
        new ProviderQuote(new BigDecimal(price), at));
  }

  @Test
  void storesTheProviderQuoteForEveryUserWithTheAssetAndOnlyOncePerUpdate() throws Exception {
    String contract = randomContract();
    CryptoApi.Actor ana = api.newActor();
    CryptoApi.Actor bia = api.newActor();
    String deAna = api.createAssetOk(ana.session(), token(contract));
    String deBia = api.createAssetOk(bia.session(), token(contract));
    Instant at = NOW.minusSeconds(120);
    // Doze casas: o banco guarda oito, e o meio de baixo arredonda para o par (HALF_EVEN).
    publish(contract, "1.234567885000", at);

    updater.update();
    updater.update();

    Row expected = new Row("coingecko", "USD", new BigDecimal("1.23456788"), at);
    assertThat(quotesOf(deAna)).hasSize(1);
    assertThat(quotesOf(deAna).getFirst().preco()).isEqualByComparingTo(expected.preco());
    assertThat(quotesOf(deAna).getFirst())
        .usingRecursiveComparison()
        .ignoringFields("preco")
        .isEqualTo(expected);
    assertThat(quotesOf(deBia)).hasSize(1);
    // Cada linha fica com o dono do ativo: a cotação de Bia não aparece para Ana nem o contrário.
    assertThat(
            jdbc.sql("SELECT usuario_id FROM cotacao WHERE ativo_id = :id::uuid")
                .param("id", deBia)
                .query(UUID.class)
                .single())
        .isEqualTo(bia.user().id());
  }

  @Test
  void aNewProviderUpdateAddsToTheSeries() throws Exception {
    String contract = randomContract();
    CryptoApi.Actor ana = api.newActor();
    String asset = api.createAssetOk(ana.session(), token(contract));
    publish(contract, "2", NOW.minusSeconds(3600));
    updater.update();
    publish(contract, "2.5", NOW.minusSeconds(60));
    updater.update();

    assertThat(quotesOf(asset))
        .extracting(Row::momento)
        .containsExactly(NOW.minusSeconds(3600), NOW.minusSeconds(60));
  }

  @Test
  void keepsTheManualQuoteTypedAtTheSameMoment() throws Exception {
    String contract = randomContract();
    CryptoApi.Actor ana = api.newActor();
    String asset = api.createAssetOk(ana.session(), token(contract));
    Instant at = Instant.parse("2026-10-23T14:00:00Z");
    api.quoteOk(ana.session(), asset, "9", at.toString());
    publish(contract, "1", at);

    updater.update();

    assertThat(quotesOf(asset)).extracting(Row::fonte).containsExactly("manual");
  }

  @Test
  void doesNotAskForAnUntrustedAssetNorStoreAnImplausibleQuote() throws Exception {
    String untrusted = randomContract();
    String tiny = randomContract();
    String huge = randomContract();
    String future = randomContract();
    CryptoApi.Actor ana = api.newActor();
    String untrustedId = api.createAssetOk(ana.session(), token(untrusted));
    jdbc.sql("UPDATE ativo_cripto SET confiavel = false WHERE id = :id::uuid")
        .param("id", untrustedId)
        .update();
    String tinyId = api.createAssetOk(ana.session(), token(tiny));
    String hugeId = api.createAssetOk(ana.session(), token(huge));
    String futureId = api.createAssetOk(ana.session(), token(future));
    publish(untrusted, "1", NOW);
    // Arredonda para zero em oito casas, e o CHECK exige preço positivo.
    publish(tiny, "0.000000004", NOW);
    // Não cabe em numeric(20,8).
    publish(huge, "1000000000000", NOW);
    // Relógio do provedor adiantado não pode virar a cotação de amanhã.
    publish(future, "1", NOW.plusSeconds(3600));

    updater.update();

    assertThat(source.requests()).doesNotContain(new AssetRef(CryptoNetwork.ETHEREUM, untrusted));
    assertThat(quotesOf(untrustedId)).isEmpty();
    assertThat(quotesOf(tinyId)).isEmpty();
    assertThat(quotesOf(hugeId)).isEmpty();
    assertThat(quotesOf(futureId)).isEmpty();
  }

  // Teste obrigatório da F14: API de cotação fora do ar não trava lançamento.
  @Test
  void providerDownKeepsTheLastQuoteAndNeverTouchesTheRequests() throws Exception {
    String contract = randomContract();
    CryptoApi.Actor ana = api.newActor();
    String asset = api.createAssetOk(ana.session(), token(contract));
    Instant at = NOW.minusSeconds(600);
    publish(contract, "3", at);
    updater.update();
    source.reset();
    source.goDown();

    assertThatCode(updater::update).doesNotThrowAnyException();
    int askedByTheJob = source.requests().size();

    api.moveOk(
        ana.session(), CryptoApi.movement(ana.cryptoId(), asset, "compra", "2", "2026-10-23", 500));
    JsonNode categories = CryptoApi.expect(api.send(ana.session(), get("/categorias"), null), 200);
    String expense = null;
    for (JsonNode category : categories) {
      if ("despesa".equals(category.get("tipo").asString())) {
        expense = category.get("id").asString();
        break;
      }
    }
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("tipo", "despesa");
    entry.put("titulo", "Feira");
    entry.put("valorCentavos", 1000);
    entry.put("categoriaId", expense);
    entry.put("contaId", ana.checkingId());
    entry.put("data", "2026-10-23");
    CryptoApi.expect(api.send(ana.session(), post("/lancamentos"), entry), 201);
    JsonNode positions = CryptoApi.expect(api.positions(ana.session(), "?data=2026-10-23"), 200);

    // Nenhuma requisição chamou a fonte: só o job fala com a CoinGecko.
    assertThat(source.requests()).hasSize(askedByTheJob);
    JsonNode quote = positions.get("posicoes").get(0).get("cotacao");
    assertThat(quote.get("precoUsd").asString()).isEqualTo("3");
    assertThat(quote.get("fonte").asString()).isEqualTo("coingecko");
    assertThat(Instant.parse(quote.get("momento").asString())).isEqualTo(at);
  }
}
