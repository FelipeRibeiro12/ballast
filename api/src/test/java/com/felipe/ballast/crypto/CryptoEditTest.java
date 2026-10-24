package com.felipe.ballast.crypto;

import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Editar e apagar ativo e cotação (ADR-058).
@IntegrationTest
class CryptoEditTest {

  private static final String NOT_FOUND = "{\"erro\":\"não encontrado\"}";
  private static final String MOMENT = "2026-10-21T15:00:00Z";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private CryptoApi api;
  private CryptoApi.Actor actor;
  private String bitcoin;

  @BeforeEach
  void setUp() throws Exception {
    api = new CryptoApi(mvc);
    actor = api.newActor();
    bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
  }

  private static Map<String, Object> edit(String symbol, String name, int decimals) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("simbolo", symbol);
    body.put("nome", name);
    body.put("decimais", decimals);
    return body;
  }

  @Test
  void editsSymbolNameAndDecimalsButNotTheIdentity() throws Exception {
    MockHttpServletResponse response =
        api.updateAsset(actor.session(), bitcoin, edit("XBT", "Bitcoin da cold", 6));

    JsonNode body = CryptoApi.expect(response, 200);
    assertThat(body.get("simbolo").asString()).isEqualTo("XBT");
    assertThat(body.get("nome").asString()).isEqualTo("Bitcoin da cold");
    assertThat(body.get("decimais").asInt()).isEqualTo(6);
    assertThat(body.get("rede").asString()).isEqualTo("bitcoin");
    assertThat(body.get("id").asString()).isEqualTo(bitcoin);
    assertThat(CryptoApi.body(api.listAssets(actor.session())).get(0).get("simbolo").asString())
        .isEqualTo("XBT");
  }

  @Test
  void refusesAnEditThatTriesToChangeNetworkOrContractOrHasBadFields() throws Exception {
    Map<String, Object> withNetwork = edit("BTC", "Bitcoin", 8);
    withNetwork.put("rede", "ethereum");
    List<Object> bad =
        List.of(
            withNetwork,
            edit("", "Bitcoin", 8),
            edit("A".repeat(21), "Bitcoin", 8),
            edit("BTC", "N".repeat(101), 8),
            edit("BTC", "Bitcoin", 19),
            edit("BTC", "Bitcoin", -1),
            Map.of("simbolo", "BTC"));
    for (Object body : bad) {
      assertThat(api.updateAsset(actor.session(), bitcoin, body).getStatus())
          .as(body.toString())
          .isEqualTo(400);
    }
  }

  @Test
  void refusesFewerDecimalsThanAMovementAlreadyUses() throws Exception {
    api.moveOk(
        actor.session(),
        movement(actor.cryptoId(), bitcoin, "compra", "0.00012345", "2026-10-20", 100));

    MockHttpServletResponse tooFew = api.updateAsset(actor.session(), bitcoin, edit("BTC", "x", 7));
    MockHttpServletResponse enough = api.updateAsset(actor.session(), bitcoin, edit("BTC", "x", 8));

    assertThat(tooFew.getStatus()).isEqualTo(409);
    assertThat(CryptoApi.text(tooFew))
        .isEqualTo("{\"erro\":\"há movimento com mais casas decimais\"}");
    assertThat(enough.getStatus()).isEqualTo(200);
  }

  @Test
  void editingAnotherUsersMissingOrMalformedAssetIs404() throws Exception {
    CryptoApi.Actor other = api.newActor();

    for (String target : new String[] {bitcoin, UUID.randomUUID().toString(), "nao-e-uuid"}) {
      MockHttpServletResponse response =
          api.updateAsset(other.session(), target, edit("BTC", "x", 8));
      assertThat(response.getStatus()).as(target).isEqualTo(404);
      assertThat(CryptoApi.text(response)).isEqualTo(NOT_FOUND);
    }
    assertThat(CryptoApi.body(api.listAssets(actor.session())).get(0).get("nome").asString())
        .isEqualTo("Bitcoin");
  }

  @Test
  void deletesAnAssetWithoutMovementsAndItsQuotes() throws Exception {
    api.quoteOk(actor.session(), bitcoin, "70000", MOMENT);

    assertThat(api.deleteAsset(actor.session(), bitcoin).getStatus()).isEqualTo(204);

    assertThat(CryptoApi.body(api.listAssets(actor.session()))).isEmpty();
    assertThat(
            jdbc.sql("SELECT count(*) FROM cotacao WHERE ativo_id = CAST(:id AS uuid)")
                .param("id", bitcoin)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(api.deleteAsset(actor.session(), bitcoin).getStatus()).isEqualTo(404);
  }

  @Test
  void anAssetWithMovementsIs409AndStaysWithItsQuotes() throws Exception {
    api.quoteOk(actor.session(), bitcoin, "70000", MOMENT);
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2026-10-20", 100));

    MockHttpServletResponse refused = api.deleteAsset(actor.session(), bitcoin);

    assertThat(refused.getStatus()).isEqualTo(409);
    assertThat(CryptoApi.text(refused)).isEqualTo("{\"erro\":\"ativo tem movimentos\"}");
    assertThat(CryptoApi.body(api.listAssets(actor.session()))).hasSize(1);
    assertThat(
            jdbc.sql("SELECT count(*) FROM cotacao WHERE ativo_id = CAST(:id AS uuid)")
                .param("id", bitcoin)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void deletingAnotherUsersMissingOrMalformedAssetIs404() throws Exception {
    CryptoApi.Actor other = api.newActor();

    for (String target : new String[] {bitcoin, UUID.randomUUID().toString(), "nao-e-uuid"}) {
      MockHttpServletResponse response = api.deleteAsset(other.session(), target);
      assertThat(response.getStatus()).as(target).isEqualTo(404);
      assertThat(CryptoApi.text(response)).isEqualTo(NOT_FOUND);
    }
    assertThat(CryptoApi.body(api.listAssets(actor.session()))).hasSize(1);
  }

  @Test
  void correctsAManualQuoteAndThePositionUsesTheNewPrice() throws Exception {
    api.quoteOk(actor.session(), bitcoin, "700000", MOMENT);
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2026-10-20", 100));

    MockHttpServletResponse response =
        api.updateQuote(actor.session(), bitcoin, MOMENT, Map.of("precoUsd", "70000.5"));

    JsonNode body = CryptoApi.expect(response, 200);
    assertThat(body.get("precoUsd").asString()).isEqualTo("70000.5");
    assertThat(body.get("fonte").asString()).isEqualTo("manual");
    assertThat(body.get("momento").asString()).isEqualTo(MOMENT);
    JsonNode position =
        CryptoApi.expect(api.positions(actor.session(), "?data=2026-10-22"), 200)
            .get("posicoes")
            .get(0);
    assertThat(position.get("cotacao").get("precoUsd").asString()).isEqualTo("70000.5");
  }

  @Test
  void refusesABadPriceOrAnExtraFieldInTheQuoteCorrection() throws Exception {
    api.quoteOk(actor.session(), bitcoin, "700000", MOMENT);

    for (Object body :
        List.<Object>of(
            Map.of("precoUsd", "0"),
            Map.of("precoUsd", "abc"),
            Map.of("precoUsd", 5),
            Map.of("momento", MOMENT),
            Map.of("precoUsd", "1", "momento", MOMENT),
            Map.of())) {
      assertThat(api.updateQuote(actor.session(), bitcoin, MOMENT, body).getStatus())
          .as(body.toString())
          .isEqualTo(400);
    }
  }

  @Test
  void deletesAManualQuote() throws Exception {
    api.quoteOk(actor.session(), bitcoin, "700000", MOMENT);

    assertThat(api.deleteQuote(actor.session(), bitcoin, MOMENT).getStatus()).isEqualTo(204);

    assertThat(api.deleteQuote(actor.session(), bitcoin, MOMENT).getStatus()).isEqualTo(404);
    assertThat(
            api.updateQuote(actor.session(), bitcoin, MOMENT, Map.of("precoUsd", "1")).getStatus())
        .isEqualTo(404);
  }

  @Test
  void aQuoteFromTheProviderIsNotEditedNorDeleted() throws Exception {
    jdbc.sql(
            "INSERT INTO cotacao (usuario_id, ativo_id, momento, preco, fonte, moeda)"
                + " VALUES (:u, CAST(:a AS uuid), :m, 70000, 'coingecko', 'USD')")
        .param("u", actor.user().id())
        .param("a", bitcoin)
        .param("m", OffsetDateTime.parse(MOMENT).withOffsetSameInstant(ZoneOffset.UTC))
        .update();

    MockHttpServletResponse edit =
        api.updateQuote(actor.session(), bitcoin, MOMENT, Map.of("precoUsd", "1"));
    MockHttpServletResponse delete = api.deleteQuote(actor.session(), bitcoin, MOMENT);

    assertThat(edit.getStatus()).isEqualTo(409);
    assertThat(delete.getStatus()).isEqualTo(409);
    assertThat(CryptoApi.text(edit)).isEqualTo("{\"erro\":\"cotação automática não se altera\"}");
  }

  @Test
  void aQuoteOfAnotherUsersAssetMissingOrWithABadMomentIs404() throws Exception {
    api.quoteOk(actor.session(), bitcoin, "700000", MOMENT);
    CryptoApi.Actor other = api.newActor();

    List<MockHttpServletResponse> responses =
        List.of(
            api.updateQuote(other.session(), bitcoin, MOMENT, Map.of("precoUsd", "1")),
            api.deleteQuote(other.session(), bitcoin, MOMENT),
            api.updateQuote(
                actor.session(), bitcoin, "2026-10-22T15:00:00Z", Map.of("precoUsd", "1")),
            api.deleteQuote(actor.session(), bitcoin, "ontem"),
            api.updateQuote(actor.session(), "nao-e-uuid", MOMENT, Map.of("precoUsd", "1")));
    for (MockHttpServletResponse response : responses) {
      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(CryptoApi.text(response)).isEqualTo(NOT_FOUND);
    }
    assertThat(
            CryptoApi.expect(api.positions(actor.session(), "?data=2026-10-22"), 200)
                .get("posicoes"))
        .isEmpty();
  }

  @Test
  void theEditAndDeleteRoutesNeedASession() throws Exception {
    assertThat(api.updateAsset(null, bitcoin, edit("BTC", "x", 8)).getStatus()).isEqualTo(401);
    assertThat(api.deleteAsset(null, bitcoin).getStatus()).isEqualTo(401);
    assertThat(api.updateQuote(null, bitcoin, MOMENT, Map.of("precoUsd", "1")).getStatus())
        .isEqualTo(401);
    assertThat(api.deleteQuote(null, bitcoin, MOMENT).getStatus()).isEqualTo(401);
  }
}
