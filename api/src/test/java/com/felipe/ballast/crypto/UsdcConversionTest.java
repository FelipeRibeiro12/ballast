package com.felipe.ballast.crypto;

import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// A cripto converte pelo USDC em reais e só cai na PTAX sem ele (ADR-077). As duas tabelas são
// globais: este teste vive em março de 2037 e limpa tudo de 2036 em diante.
@IntegrationTest
class UsdcConversionTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private CryptoApi api;
  private CryptoApi.Actor actor;
  private String bitcoin;

  @BeforeEach
  void setUp() throws Exception {
    clean();
    clock.freeze(Instant.parse("2037-03-20T15:00:00Z"));
    api = new CryptoApi(mvc);
    actor = api.newActor();
    bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    clean();
  }

  private void clean() {
    jdbc.sql("DELETE FROM indice WHERE serie = 'dolar' AND data >= DATE '2036-01-01'").update();
    jdbc.sql("DELETE FROM cotacao_usdc_brl WHERE momento >= TIMESTAMPTZ '2036-06-01 00:00:00Z'")
        .update();
  }

  private void ptax(String date, String value) {
    jdbc.sql("INSERT INTO indice (serie, data, valor) VALUES ('dolar', :d, CAST(:v AS numeric))")
        .param("d", LocalDate.parse(date))
        .param("v", value)
        .update();
  }

  private void usdc(String moment, String value) {
    jdbc.sql("INSERT INTO cotacao_usdc_brl (momento, preco) VALUES (:m, CAST(:v AS numeric))")
        .param("m", OffsetDateTime.ofInstant(Instant.parse(moment), ZoneOffset.UTC))
        .param("v", value)
        .update();
  }

  private JsonNode positions(CryptoApi.Actor who, String date) throws Exception {
    return CryptoApi.expect(api.positions(who.session(), "?data=" + date), 200);
  }

  private JsonNode netWorth(CryptoApi.Actor who, String date) throws Exception {
    return CryptoApi.expect(api.netWorth(who.session(), "?data=" + date), 200);
  }

  private static JsonNode accountOf(JsonNode body, String accountId) {
    for (JsonNode account : body.get("contas")) {
      if (account.get("contaId").asString().equals(accountId)) {
        return account;
      }
    }
    return null;
  }

  @Test
  void theCostAndTheValueUseTheUsdcAndIgnoreThePtaxThatIsThere() throws Exception {
    ptax("2037-03-02", "5.0");
    usdc("2037-03-02T12:00:00Z", "5.5");

    JsonNode created =
        CryptoApi.expect(
            api.move(
                actor.session(),
                movement(actor.cryptoId(), bitcoin, "compra", "0.5", "2037-03-03", 3_000_000)),
            201);
    api.quoteOk(actor.session(), bitcoin, "70000.5", "2037-03-10T12:00:00-03:00");
    JsonNode body = positions(actor, "2037-03-10");

    assertThat(created.get("custoCentavos").asLong()).isEqualTo(16_500_000);
    JsonNode dollar = body.get("dolar");
    assertThat(dollar.get("valor").asString()).isEqualTo("5.5");
    assertThat(dollar.get("fonte").asString()).isEqualTo("USDC");
    assertThat(dollar.get("data").asString()).isEqualTo("2037-03-02");
    assertThat(Instant.parse(dollar.get("momento").asString()))
        .isEqualTo(Instant.parse("2037-03-02T12:00:00Z"));
    JsonNode position = body.get("posicoes").get(0);
    assertThat(position.get("valorUsdCentavos").asLong()).isEqualTo(3_500_025);
    // 35.000,25 x 5,5 = 192.501,375: o meio centavo vai para o par (HALF_EVEN).
    assertThat(position.get("valorCentavos").asLong()).isEqualTo(19_250_138);
    assertThat(position.get("custoCentavos").asLong()).isEqualTo(16_500_000);
  }

  @Test
  void saturdayAndSundayUseTheLastUsdcOfTheWeekendAndNotTheFridayPtax() throws Exception {
    // 06/03/2037 é sexta. A leitura de sexta é das 23:30 em São Paulo e a de sábado das 23:00.
    ptax("2037-03-02", "5.0");
    ptax("2037-03-06", "5.0");
    usdc("2037-03-07T02:30:00Z", "5.10");
    usdc("2037-03-08T02:00:00Z", "5.30");
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2037-03-05", 100_000));
    api.quoteOk(actor.session(), bitcoin, "1000", "2037-03-05T12:00:00Z");

    JsonNode friday = positions(actor, "2037-03-06");
    JsonNode saturday = positions(actor, "2037-03-07");
    JsonNode sunday = positions(actor, "2037-03-08");

    assertThat(friday.get("posicoes").get(0).get("valorCentavos").asLong()).isEqualTo(510_000);
    assertThat(saturday.get("posicoes").get(0).get("valorCentavos").asLong()).isEqualTo(530_000);
    assertThat(sunday.get("posicoes").get(0).get("valorCentavos").asLong()).isEqualTo(530_000);
    assertThat(sunday.get("dolar").get("fonte").asString()).isEqualTo("USDC");
    assertThat(sunday.get("dolar").get("data").asString()).isEqualTo("2037-03-07");
    assertThat(netWorth(actor, "2037-03-07").get("criptoCentavos").asLong()).isEqualTo(530_000);
  }

  @Test
  void thePtaxOnlyAnswersWhenThereIsNoUsdcUpToTheDate() throws Exception {
    ptax("2037-03-02", "5.1");
    usdc("2037-03-10T12:00:00Z", "5.5");
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2037-03-03", 100_000));
    api.quoteOk(actor.session(), bitcoin, "1000", "2037-03-03T12:00:00Z");

    JsonNode before = positions(actor, "2037-03-05");
    JsonNode after = positions(actor, "2037-03-10");

    assertThat(before.get("dolar").get("fonte").asString()).isEqualTo("PTAX");
    assertThat(before.get("dolar").get("momento").isNull()).isTrue();
    assertThat(before.get("dolar").get("data").asString()).isEqualTo("2037-03-02");
    assertThat(before.get("dolar").get("valor").asString()).isEqualTo("5.1");
    assertThat(before.get("posicoes").get(0).get("valorCentavos").asLong()).isEqualTo(510_000);
    assertThat(after.get("dolar").get("fonte").asString()).isEqualTo("USDC");
    assertThat(after.get("posicoes").get(0).get("valorCentavos").asLong()).isEqualTo(550_000);
  }

  @Test
  void withNeitherOnlyTheReaisAreLeftOutAndTheNetWorthStillAnswers() throws Exception {
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2037-03-01", 100_000));
    api.quoteOk(actor.session(), bitcoin, "2000", "2037-03-01T12:00:00Z");

    JsonNode body = positions(actor, "2037-03-01");
    JsonNode netWorth = netWorth(actor, "2037-03-01");

    assertThat(body.get("dolar").isNull()).isTrue();
    JsonNode position = body.get("posicoes").get(0);
    assertThat(position.get("valorUsdCentavos").asLong()).isEqualTo(200_000);
    assertThat(position.get("valorCentavos").isNull()).isTrue();
    JsonNode account = accountOf(body, actor.cryptoId());
    assertThat(account.get("valorUsdCentavos").asLong()).isEqualTo(200_000);
    assertThat(account.get("valorCentavos").isNull()).isTrue();
    assertThat(netWorth.get("criptoCentavos").asLong()).isZero();
    assertThat(netWorth.get("criptoUsdCentavos").asLong()).isEqualTo(200_000);
    assertThat(netWorth.get("semCotacao")).hasSize(1);
  }

  @Test
  void anOldQuoteInReaisWithoutAnyDollarLeavesTheDollarTotalNull() throws Exception {
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2037-03-01", 100_000));
    jdbc.sql(
            "INSERT INTO cotacao (usuario_id, ativo_id, momento, preco, fonte, moeda) VALUES"
                + " (:u, CAST(:a AS uuid), TIMESTAMPTZ '2037-03-01 12:00:00Z', 2500, 'manual',"
                + " 'BRL')")
        .param("u", actor.user().id())
        .param("a", bitcoin)
        .update();

    JsonNode netWorth = netWorth(actor, "2037-03-01");

    assertThat(netWorth.get("criptoCentavos").asLong()).isEqualTo(250_000);
    assertThat(netWorth.get("criptoUsdCentavos").isNull()).isTrue();
    JsonNode account = accountOf(positions(actor, "2037-03-01"), actor.cryptoId());
    assertThat(account.get("valorCentavos").asLong()).isEqualTo(250_000);
    assertThat(account.get("valorUsdCentavos").isNull()).isTrue();
  }

  @Test
  void eachCurrencyRoundsOnceFromTheFullValueAndNeverFromTheOtherOneRounded() throws Exception {
    usdc("2037-03-02T12:00:00Z", "5.5");
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2037-03-03", 100_000));
    api.quoteOk(actor.session(), bitcoin, "10.0149", "2037-03-03T12:00:00Z");

    JsonNode body = positions(actor, "2037-03-10");

    JsonNode position = body.get("posicoes").get(0);
    assertThat(position.get("valorUsdCentavos").asLong()).isEqualTo(1001);
    // 10,0149 x 5,5 = 55,08195, que são 5.508 centavos. Converter os US$ 10,01 já arredondados dá
    // 55,055 e 5.506: 2 centavos a menos.
    assertThat(position.get("valorCentavos").asLong()).isEqualTo(5508);
    JsonNode account = accountOf(body, actor.cryptoId());
    assertThat(account.get("valorCentavos").asLong()).isEqualTo(5508);
    assertThat(account.get("valorUsdCentavos").asLong()).isEqualTo(1001);
  }

  @Test
  void theAccountSumsTheFullValuesOfItsPositionsAndRoundsOnce() throws Exception {
    usdc("2037-03-02T12:00:00Z", "5");
    String ether = api.createAssetOk(actor.session(), ethereum());
    for (String asset : new String[] {bitcoin, ether}) {
      api.moveOk(
          actor.session(), movement(actor.cryptoId(), asset, "compra", "1", "2037-03-03", 100));
      api.quoteOk(actor.session(), asset, "0.004", "2037-03-03T12:00:00Z");
    }

    JsonNode body = positions(actor, "2037-03-10");

    // Cada posição vale US$ 0,004, que arredonda para zero centavo de dólar. As duas juntas valem
    // US$ 0,008, que é 1 centavo.
    for (JsonNode position : body.get("posicoes")) {
      assertThat(position.get("valorUsdCentavos").asLong()).isZero();
      assertThat(position.get("valorCentavos").asLong()).isEqualTo(2);
    }
    JsonNode account = accountOf(body, actor.cryptoId());
    assertThat(account.get("valorUsdCentavos").asLong()).isEqualTo(1);
    assertThat(account.get("valorCentavos").asLong()).isEqualTo(4);
  }

  @Test
  void everyActiveCryptoAccountIsListedAndAnEmptyOneIsZero() throws Exception {
    usdc("2037-03-02T12:00:00Z", "5");
    String empty = api.createAccountOk(actor.session(), "Vazia", "cripto");

    JsonNode body = positions(actor, "2037-03-10");

    assertThat(body.get("contas")).hasSize(2);
    JsonNode account = accountOf(body, empty);
    assertThat(account.get("valorCentavos").asLong()).isZero();
    assertThat(account.get("valorUsdCentavos").asLong()).isZero();
    assertThat(accountOf(body, actor.checkingId())).isNull();
  }

  @Test
  void anotherUserNeverSeesTheAccountsOrTheValuesOfThisOne() throws Exception {
    usdc("2037-03-02T12:00:00Z", "5");
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "1", "2037-03-03", 100_000));
    api.quoteOk(actor.session(), bitcoin, "1000", "2037-03-03T12:00:00Z");
    CryptoApi.Actor other = api.newActor();

    JsonNode body = positions(other, "2037-03-10");

    assertThat(body.get("posicoes")).isEmpty();
    assertThat(body.get("contas")).hasSize(1);
    assertThat(accountOf(body, actor.cryptoId())).isNull();
    assertThat(accountOf(body, other.cryptoId()).get("valorCentavos").asLong()).isZero();
    JsonNode netWorth = netWorth(other, "2037-03-10");
    assertThat(netWorth.get("criptoCentavos").asLong()).isZero();
    assertThat(netWorth.get("criptoUsdCentavos").asLong()).isZero();
  }

  @Test
  void theNetWorthClosesContributionPlusAppreciationAndTheAccountMatchesTheTotal()
      throws Exception {
    usdc("2037-03-02T12:00:00Z", "5.5");
    api.moveOk(
        actor.session(),
        movement(actor.cryptoId(), bitcoin, "compra", "0.5", "2037-03-03", 3_000_000));
    api.quoteOk(actor.session(), bitcoin, "70000.5", "2037-03-10T12:00:00-03:00");

    JsonNode netWorth = netWorth(actor, "2037-03-10");
    JsonNode account = accountOf(positions(actor, "2037-03-10"), actor.cryptoId());

    assertThat(netWorth.get("criptoCentavos").asLong()).isEqualTo(19_250_138);
    assertThat(netWorth.get("criptoUsdCentavos").asLong()).isEqualTo(3_500_025);
    assertThat(netWorth.get("criptoAportadoCentavos").asLong()).isEqualTo(16_500_000);
    assertThat(
            netWorth.get("criptoAportadoCentavos").asLong()
                + netWorth.get("criptoValorizacaoCentavos").asLong())
        .isEqualTo(netWorth.get("criptoCentavos").asLong());
    assertThat(account.get("valorCentavos").asLong())
        .isEqualTo(netWorth.get("criptoCentavos").asLong());
    assertThat(account.get("valorUsdCentavos").asLong())
        .isEqualTo(netWorth.get("criptoUsdCentavos").asLong());
  }

  private static Map<String, Object> ethereum() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rede", "ethereum");
    body.put("simbolo", "ETH");
    body.put("nome", "Ether");
    body.put("decimais", 18);
    return body;
  }
}
