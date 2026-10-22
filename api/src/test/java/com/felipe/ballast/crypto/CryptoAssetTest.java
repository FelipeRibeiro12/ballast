package com.felipe.ballast.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class CryptoAssetTest {

  private static final String INVALID_CONTRACT = "{\"erro\":\"contrato inválido para a rede\"}";
  private static final String USDC_MIXED_CASE = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
  private static final String USDC_SOLANA = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

  @Autowired MockMvc mvc;
  private CryptoApi api;
  private CryptoApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new CryptoApi(mvc);
    actor = api.newActor();
  }

  private static Map<String, Object> token(String network, String contract, String symbol) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rede", network);
    body.put("contrato", contract);
    body.put("simbolo", symbol);
    body.put("decimais", 6);
    return body;
  }

  private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
    Map<String, Object> copy = new LinkedHashMap<>(body);
    copy.put(key, value);
    return copy;
  }

  @Test
  void createsANativeAssetTrustedAndWithoutContract() throws Exception {
    MockHttpServletResponse created = api.createAsset(actor.session(), CryptoApi.bitcoin());

    assertThat(created.getStatus()).isEqualTo(201);
    JsonNode body = CryptoApi.body(created);
    assertThat(body.get("id").asString()).isNotBlank();
    assertThat(body.get("rede").asString()).isEqualTo("bitcoin");
    assertThat(body.get("contrato").isNull()).isTrue();
    assertThat(body.get("simbolo").asString()).isEqualTo("BTC");
    assertThat(body.get("nome").asString()).isEqualTo("Bitcoin");
    assertThat(body.get("decimais").asInt()).isEqualTo(8);
    assertThat(body.get("confiavel").asBoolean()).isTrue();
  }

  @Test
  void storesAnEvmContractInLowerCase() throws Exception {
    MockHttpServletResponse created =
        api.createAsset(actor.session(), token("ethereum", USDC_MIXED_CASE, "USDC"));

    assertThat(created.getStatus()).isEqualTo(201);
    assertThat(CryptoApi.body(created).get("contrato").asString())
        .isEqualTo(USDC_MIXED_CASE.toLowerCase());
  }

  @Test
  void acceptsASolanaMintAsIs() throws Exception {
    MockHttpServletResponse created =
        api.createAsset(actor.session(), token("solana", USDC_SOLANA, "USDC"));

    assertThat(created.getStatus()).isEqualTo(201);
    assertThat(CryptoApi.body(created).get("contrato").asString()).isEqualTo(USDC_SOLANA);
  }

  @Test
  void refusesAContractThatDoesNotFitTheNetwork() throws Exception {
    List<Map<String, Object>> bad =
        List.of(
            token("bitcoin", USDC_MIXED_CASE, "X"),
            token("ethereum", USDC_SOLANA, "X"),
            token("base", "0x123", "X"),
            token("polygon", USDC_MIXED_CASE + "0", "X"),
            token("solana", USDC_MIXED_CASE, "X"),
            token("solana", "0OIl" + USDC_SOLANA.substring(4), "X"),
            token("arbitrum", "", "X"));
    for (Map<String, Object> body : bad) {
      MockHttpServletResponse refused = api.createAsset(actor.session(), body);

      assertThat(refused.getStatus()).as(body.toString()).isEqualTo(400);
      assertThat(CryptoApi.text(refused)).as(body.toString()).isEqualTo(INVALID_CONTRACT);
    }
    assertThat(CryptoApi.body(api.listAssets(actor.session()))).isEmpty();
  }

  @Test
  void theSameNetworkAndContractTwiceIs409EvenInAnotherCase() throws Exception {
    api.createAssetOk(actor.session(), token("ethereum", USDC_MIXED_CASE, "USDC"));

    MockHttpServletResponse again =
        api.createAsset(actor.session(), token("ethereum", USDC_MIXED_CASE.toLowerCase(), "USD"));

    assertThat(again.getStatus()).isEqualTo(409);
    assertThat(CryptoApi.text(again)).isEqualTo("{\"erro\":\"ativo já cadastrado\"}");
  }

  @Test
  void twoNativeAssetsOfTheSameNetworkIs409() throws Exception {
    api.createAssetOk(actor.session(), CryptoApi.bitcoin());

    MockHttpServletResponse again =
        api.createAsset(actor.session(), with(CryptoApi.bitcoin(), "simbolo", "XBT"));

    assertThat(again.getStatus()).isEqualTo(409);
  }

  @Test
  void theSameContractOnAnotherNetworkOrForAnotherUserIsFine() throws Exception {
    api.createAssetOk(actor.session(), token("ethereum", USDC_MIXED_CASE, "USDC"));
    CryptoApi.Actor other = api.newActor();

    assertThat(api.createAsset(actor.session(), token("base", USDC_MIXED_CASE, "USDC")).getStatus())
        .isEqualTo(201);
    assertThat(
            api.createAsset(other.session(), token("ethereum", USDC_MIXED_CASE, "USDC"))
                .getStatus())
        .isEqualTo(201);
  }

  @Test
  void listsOnlyTheUsersAssetsBySymbolIgnoringCaseThenNetwork() throws Exception {
    api.createAssetOk(actor.session(), token("solana", USDC_SOLANA, "usdc"));
    api.createAssetOk(actor.session(), token("ethereum", USDC_MIXED_CASE, "USDC"));
    api.createAssetOk(actor.session(), CryptoApi.bitcoin());
    CryptoApi.Actor other = api.newActor();
    api.createAssetOk(other.session(), with(CryptoApi.bitcoin(), "simbolo", "AAA"));

    JsonNode list = CryptoApi.body(api.listAssets(actor.session()));

    assertThat(list).hasSize(3);
    assertThat(list.get(0).get("simbolo").asString()).isEqualTo("BTC");
    assertThat(list.get(1).get("rede").asString()).isEqualTo("ethereum");
    assertThat(list.get(2).get("rede").asString()).isEqualTo("solana");
  }

  @Test
  void stripsTheSymbolAndTurnsABlankNameIntoNull() throws Exception {
    Map<String, Object> body = with(CryptoApi.bitcoin(), "simbolo", "  BTC ");
    body.put("nome", "   ");

    JsonNode created = CryptoApi.body(api.createAsset(actor.session(), body));

    assertThat(created.get("simbolo").asString()).isEqualTo("BTC");
    assertThat(created.get("nome").isNull()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 19})
  void refusesDecimalsOutOfRange(int decimals) throws Exception {
    MockHttpServletResponse refused =
        api.createAsset(actor.session(), with(CryptoApi.bitcoin(), "decimais", decimals));

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused))
        .isEqualTo("{\"erro\":\"decimais deve ser um inteiro entre 0 e 18\"}");
  }

  @Test
  void refusesABadSymbolNameNetworkOrUndeclaredField() throws Exception {
    List<Object> bad =
        List.of(
            with(CryptoApi.bitcoin(), "simbolo", ""),
            with(CryptoApi.bitcoin(), "simbolo", "A".repeat(21)),
            with(CryptoApi.bitcoin(), "simbolo", "BT\u0000C"),
            with(CryptoApi.bitcoin(), "nome", "N".repeat(101)),
            with(CryptoApi.bitcoin(), "rede", "dogecoin"),
            with(CryptoApi.bitcoin(), "rede", "Bitcoin"),
            with(CryptoApi.bitcoin(), "confiavel", false),
            with(CryptoApi.bitcoin(), "decimais", "8"),
            with(CryptoApi.bitcoin(), "decimais", 8.5),
            "{\"simbolo\":\"BTC\",\"decimais\":8}",
            "{\"rede\":\"bitcoin\",\"decimais\":8}",
            "{\"rede\":\"bitcoin\",\"simbolo\":\"BTC\"}");
    for (Object body : bad) {
      assertThat(api.createAsset(actor.session(), body).getStatus())
          .as(body.toString())
          .isEqualTo(400);
    }
    assertThat(CryptoApi.body(api.listAssets(actor.session()))).isEmpty();
  }

  @Test
  void withoutSessionIs401() throws Exception {
    assertThat(api.createAsset(null, CryptoApi.bitcoin()).getStatus()).isEqualTo(401);
    assertThat(api.listAssets(null).getStatus()).isEqualTo(401);
  }
}
