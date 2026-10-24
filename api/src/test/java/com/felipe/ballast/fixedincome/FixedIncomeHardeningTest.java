package com.felipe.ballast.fixedincome;

import static com.felipe.ballast.fixedincome.FixedIncomeApi.title;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.FakeIndexSource;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.crypto.CryptoApi;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Vencimento, desfazer resgate, marcação fora do período, título sem valor e aviso de índice
// (ADR-058). indice é global: este teste vive em março de 2037, longe dos outros.
@IntegrationTest
class FixedIncomeHardeningTest {

  private static final long CAP_CENTS = 9_007_199_254_740_991L;

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  @Autowired FakeIndexSource source;
  private CryptoApi base;
  private FixedIncomeApi api;
  private CryptoApi.Actor actor;
  private String broker;

  @BeforeEach
  void setUp() throws Exception {
    clearIndices();
    for (LocalDate day = LocalDate.of(2037, 3, 1);
        !day.isAfter(LocalDate.of(2037, 3, 30));
        day = day.plusDays(1)) {
      if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
        index("cdi", day, "0.05");
      }
    }
    index("ipca", LocalDate.of(2037, 2, 1), "1");
    index("ipca", LocalDate.of(2037, 3, 1), "1");
    clock.freeze(Instant.parse("2037-03-31T15:00:00Z"));
    base = new CryptoApi(mvc);
    api = new FixedIncomeApi(base);
    actor = base.newActor();
    broker = base.createAccountOk(actor.session(), "Corretora", "investimento");
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    source.reset();
    clearIndices();
  }

  private void clearIndices() {
    jdbc.sql("DELETE FROM indice WHERE data BETWEEN DATE '2037-01-01' AND DATE '2037-12-31'")
        .update();
  }

  private void index(String series, LocalDate date, String value) {
    jdbc.sql("INSERT INTO indice (serie, data, valor) VALUES (:s, :d, CAST(:v AS numeric))")
        .param("s", series)
        .param("d", date)
        .param("v", value)
        .update();
  }

  private JsonNode listed(String query) throws Exception {
    return CryptoApi.expect(api.list(actor.session(), query), 200);
  }

  private JsonNode first(String query) throws Exception {
    return listed(query).get("titulos").get(0);
  }

  private void insertMark(String titleId, String date, String value) {
    jdbc.sql(
            "INSERT INTO marcacao_renda_fixa (usuario_id, titulo_id, data, valor)"
                + " VALUES (:u, CAST(:t AS uuid), CAST(:d AS date), CAST(:v AS numeric))")
        .param("u", actor.user().id())
        .param("t", titleId)
        .param("d", date)
        .param("v", value)
        .update();
  }

  private String cdiTitle(String maturity) throws Exception {
    Map<String, Object> body = title(broker, "lci", "cdi", "100", 100_000, "2037-03-01");
    if (maturity != null) {
      body.put("vencimento", maturity);
    }
    return api.createOk(actor.session(), body);
  }

  @Test
  void aMaturedTitleStopsEarningOnTheMaturityDate() throws Exception {
    cdiTitle("2037-03-10");

    JsonNode onMaturity = first("?data=2037-03-10");
    JsonNode later = first("?data=2037-03-25");
    JsonNode before = first("?data=2037-03-05");

    assertThat(later.get("valorBrutoCentavos").asLong())
        .isEqualTo(onMaturity.get("valorBrutoCentavos").asLong());
    assertThat(later.get("valorLiquidoCentavos").asLong())
        .isEqualTo(onMaturity.get("valorLiquidoCentavos").asLong());
    assertThat(later.get("rendimentoCentavos").asLong()).isPositive();
    assertThat(onMaturity.get("vencido").asBoolean()).isTrue();
    assertThat(later.get("vencido").asBoolean()).isTrue();
    assertThat(before.get("vencido").asBoolean()).isFalse();
    assertThat(before.get("valorBrutoCentavos").asLong())
        .isLessThan(onMaturity.get("valorBrutoCentavos").asLong());
  }

  @Test
  void aTitleWithoutMaturityKeepsEarningAndIsNotMatured() throws Exception {
    cdiTitle(null);

    assertThat(first("?data=2037-03-25").get("vencido").asBoolean()).isFalse();
    assertThat(first("?data=2037-03-25").get("valorBrutoCentavos").asLong())
        .isGreaterThan(first("?data=2037-03-10").get("valorBrutoCentavos").asLong());
  }

  @Test
  void undoingTheRedemptionPutsTheTitleBackToWork() throws Exception {
    String id = cdiTitle(null);
    api.redeem(actor.session(), id, "2037-03-12", 100_500);
    assertThat(first("?data=2037-03-20").get("valorBrutoCentavos").asLong()).isZero();

    assertThat(api.undoRedemption(actor.session(), id).getStatus()).isEqualTo(204);

    JsonNode item = first("?data=2037-03-20");
    assertThat(item.get("resgate").isNull()).isTrue();
    assertThat(item.get("valorBrutoCentavos").asLong()).isGreaterThan(100_000);
  }

  @Test
  void undoingTheRedemptionTwiceOrOfATitleNeverRedeemedIsStill204() throws Exception {
    String redeemed = cdiTitle(null);
    String never = cdiTitle(null);
    api.redeem(actor.session(), redeemed, "2037-03-12", 100_500);

    assertThat(api.undoRedemption(actor.session(), redeemed).getStatus()).isEqualTo(204);
    assertThat(api.undoRedemption(actor.session(), redeemed).getStatus()).isEqualTo(204);
    assertThat(api.undoRedemption(actor.session(), never).getStatus()).isEqualTo(204);
  }

  @Test
  void undoingTheRedemptionOfAnotherUsersMissingOrMalformedTitleIs404() throws Exception {
    String id = cdiTitle(null);
    api.redeem(actor.session(), id, "2037-03-12", 100_500);
    CryptoApi.Actor other = base.newActor();

    for (String target : new String[] {id, UUID.randomUUID().toString(), "nao-e-uuid"}) {
      var response = api.undoRedemption(other.session(), target);
      assertThat(response.getStatus()).as(target).isEqualTo(404);
      assertThat(CryptoApi.text(response)).isEqualTo("{\"erro\":\"não encontrado\"}");
    }
    assertThat(first("?data=2037-03-20").get("resgate").isNull()).isFalse();
  }

  @Test
  void undoingTheRedemptionWithoutSessionIs401() throws Exception {
    assertThat(api.undoRedemption(null, UUID.randomUUID().toString()).getStatus()).isEqualTo(401);
  }

  @Test
  void refusesARedemptionBeforeAMarkAlreadySaved() throws Exception {
    String id = cdiTitle(null);
    api.mark(actor.session(), id, "2037-03-15", 100_800);

    var refused = api.redeem(actor.session(), id, "2037-03-12", 100_500);

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused))
        .isEqualTo("{\"erro\":\"resgate não pode ser antes de uma marcação\"}");
    assertThat(api.redeem(actor.session(), id, "2037-03-15", 100_900).getStatus()).isEqualTo(200);
  }

  @Test
  void refusesMovingTheApplicationAfterAMark() throws Exception {
    String id = cdiTitle(null);
    api.mark(actor.session(), id, "2037-03-10", 100_400);

    var refused =
        api.update(actor.session(), id, title(broker, "lci", "cdi", "100", 100_000, "2037-03-12"));

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused))
        .isEqualTo("{\"erro\":\"dataAplicacao não pode ser depois de uma marcação\"}");
    assertThat(
            api.update(
                    actor.session(), id, title(broker, "lci", "cdi", "100", 100_000, "2037-03-10"))
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  void marksOutsideTheTitlePeriodAreHiddenFromTheListAndTheValue() throws Exception {
    String id = cdiTitle(null);
    long untouched = first("?data=2037-03-20").get("valorBrutoCentavos").asLong();
    // Gravada antes das regras novas: antes da aplicação.
    insertMark(id, "2037-02-20", "999.00");

    JsonNode item = first("?data=2037-03-20");

    assertThat(item.get("ultimaMarcacao").isNull()).isTrue();
    assertThat(item.get("valorBrutoCentavos").asLong()).isEqualTo(untouched);

    // E depois do resgate: o resgate veio antes da marcação existir.
    api.redeem(actor.session(), id, "2037-03-22", 100_700);
    insertMark(id, "2037-03-25", "5000.00");
    JsonNode redeemed = first("?data=2037-03-30");
    assertThat(redeemed.get("ultimaMarcacao").isNull()).isTrue();
    assertThat(redeemed.get("resgate").get("valorCentavos").asLong()).isEqualTo(100_700);
    assertThat(first("?data=2037-03-20").get("valorBrutoCentavos").asLong()).isEqualTo(untouched);
  }

  @Test
  void aTitleAtTheCapComesWithoutValueAndTheOthersStay() throws Exception {
    Map<String, Object> huge = title(broker, "lci", "cdi", "100", CAP_CENTS, "2037-02-27");
    String hugeId = api.createOk(actor.session(), huge);
    cdiTitle(null);

    JsonNode body = listed("?data=2037-03-20");

    JsonNode excluded = body.get("titulos").get(0);
    JsonNode normal = body.get("titulos").get(1);
    assertThat(excluded.get("id").asString()).isEqualTo(hugeId);
    assertThat(excluded.get("valorBrutoCentavos").isNull()).isTrue();
    assertThat(excluded.get("valorLiquidoCentavos").isNull()).isTrue();
    assertThat(excluded.get("motivoSemValor").asString()).isEqualTo("fora do limite");
    assertThat(normal.get("valorBrutoCentavos").asLong()).isGreaterThan(100_000);
    assertThat(normal.get("motivoSemValor").isNull()).isTrue();
  }

  @Test
  void theNetWorthAddsTheOthersAndFlagsTheTitleWithoutValue() throws Exception {
    String hugeId =
        api.createOk(actor.session(), title(broker, "lci", "cdi", "100", CAP_CENTS, "2037-02-27"));
    cdiTitle(null);
    JsonNode normal = listed("?data=2037-03-20").get("titulos").get(1);

    JsonNode netWorth = CryptoApi.expect(base.netWorth(actor.session(), "?data=2037-03-20"), 200);

    assertThat(netWorth.get("rendaFixaCentavos").asLong())
        .isEqualTo(normal.get("valorLiquidoCentavos").asLong());
    assertThat(netWorth.get("rendaFixaSemValor")).hasSize(1);
    assertThat(netWorth.get("rendaFixaSemValor").get(0).get("tituloId").asString())
        .isEqualTo(hugeId);
    assertThat(netWorth.get("rendaFixaSemValor").get(0).get("motivo").asString())
        .isEqualTo("fora do limite");
  }

  @Test
  void aTitleStartedBeforeTheFirstCdiWarnsAboutTheIndex() throws Exception {
    api.createOk(actor.session(), title(broker, "lci", "cdi", "100", 100_000, "2037-02-10"));

    JsonNode item = first("?data=2037-03-20");

    assertThat(item.get("avisoIndice").asString()).contains("CDI").contains("02/03/2037");
  }

  @Test
  void aCdiThatStopsLongBeforeTheDateWarnsAboutTheIndex() throws Exception {
    cdiTitle(null);
    jdbc.sql("DELETE FROM indice WHERE serie = 'cdi' AND data > DATE '2037-03-10'").update();

    JsonNode item = first("?data=2037-03-31");

    assertThat(item.get("avisoIndice").asString()).contains("CDI").contains("10/03/2037");
  }

  @Test
  void withoutAnyCdiOrIpcaTheTitleSaysSo() throws Exception {
    api.createOk(actor.session(), title(broker, "lca", "ipca", "6", 100_000, "2037-03-01"));
    jdbc.sql("DELETE FROM indice WHERE serie = 'ipca' AND data >= DATE '2037-01-01'").update();

    JsonNode item = first("?data=2037-03-20");

    assertThat(item.get("avisoIndice").asString()).contains("IPCA");
    jdbc.sql("DELETE FROM indice WHERE serie = 'cdi' AND data >= DATE '2037-01-01'").update();
    assertThat(first("?data=2037-03-20").get("avisoIndice").asString()).contains("CDI");
  }

  @Test
  void aTitleWithFullIndexCoverageHasNoWarning() throws Exception {
    cdiTitle(null);
    api.createOk(actor.session(), title(broker, "lca", "ipca", "6", 100_000, "2037-03-01"));

    JsonNode titles = listed("?data=2037-03-20").get("titulos");

    assertThat(titles.get(0).get("avisoIndice").isNull()).isTrue();
    assertThat(titles.get(1).get("avisoIndice").isNull()).isTrue();
  }
}
