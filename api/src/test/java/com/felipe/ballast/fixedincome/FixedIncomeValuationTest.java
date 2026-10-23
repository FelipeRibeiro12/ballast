package com.felipe.ballast.fixedincome;

import static com.felipe.ballast.fixedincome.FixedIncomeApi.title;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.FakeIndexSource;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.crypto.CryptoApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// indice é global e o banco é compartilhado: este teste vive em março de 2035, com CDI de 0,05%
// ao dia em todo dia de semana e IPCA de 1% em fevereiro e março.
@IntegrationTest
class FixedIncomeValuationTest {

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
    for (LocalDate day = LocalDate.of(2035, 3, 1);
        !day.isAfter(LocalDate.of(2035, 3, 30));
        day = day.plusDays(1)) {
      if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
        index("cdi", day, "0.05");
      }
    }
    index("ipca", LocalDate.of(2035, 2, 1), "1");
    index("ipca", LocalDate.of(2035, 3, 1), "1");
    clock.freeze(Instant.parse("2035-03-31T15:00:00Z"));
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
    jdbc.sql("DELETE FROM indice WHERE data BETWEEN DATE '2035-01-01' AND DATE '2035-12-31'")
        .update();
  }

  private void index(String series, LocalDate date, String value) {
    jdbc.sql("INSERT INTO indice (serie, data, valor) VALUES (:s, :d, CAST(:v AS numeric))")
        .param("s", series)
        .param("d", date)
        .param("v", value)
        .update();
  }

  private JsonNode valued(String query) throws Exception {
    return CryptoApi.expect(api.list(actor.session(), query), 200);
  }

  private static long cents(BigDecimal reais) {
    return reais.setScale(2, RoundingMode.HALF_EVEN).movePointRight(2).longValueExact();
  }

  @Test
  void aCdiTitleCompoundsEachBusinessDayBeforeTheDate() throws Exception {
    api.createOk(actor.session(), title(broker, "lci", "cdi", "100", 100_000, "2035-03-01"));

    JsonNode body = valued("?data=2035-03-15");

    // De 01/03 (quinta) a 14/03: 10 dias úteis a 0,05%.
    long expected = cents(new BigDecimal("1000").multiply(new BigDecimal("1.0005").pow(10)));
    JsonNode item = body.get("titulos").get(0);
    assertThat(body.get("data").asString()).isEqualTo("2035-03-15");
    assertThat(item.get("valorBrutoCentavos").asLong()).isEqualTo(expected);
    assertThat(item.get("valorLiquidoCentavos").asLong()).isEqualTo(expected);
    assertThat(item.get("rendimentoCentavos").asLong()).isEqualTo(expected - 100_000);
    assertThat(item.get("irCentavos").asLong()).isZero();
  }

  @Test
  void aPercentOfTheCdiAppliesTheShareToEachDay() throws Exception {
    api.createOk(actor.session(), title(broker, "lca", "cdi", "90", 100_000, "2035-03-01"));

    long expected = cents(new BigDecimal("1000").multiply(new BigDecimal("1.00045").pow(10)));

    assertThat(valued("?data=2035-03-15").get("titulos").get(0).get("valorBrutoCentavos").asLong())
        .isEqualTo(expected);
  }

  @Test
  void aCdbShowsGrossAndNetAfterIofAndIncomeTax() throws Exception {
    api.createOk(actor.session(), title(broker, "cdb", "prefixado", "12", 100_000, "2035-03-01"));

    JsonNode item = valued("?data=2035-03-15").get("titulos").get(0);

    BigDecimal gross =
        new BigDecimal("1000")
            .multiply(FixedIncomeMath.prefixedFactor(new BigDecimal("12"), 10))
            .setScale(2, RoundingMode.HALF_EVEN);
    FixedIncomeMath.Taxes taxes =
        FixedIncomeMath.taxes(FixedIncomeType.CDB, new BigDecimal("1000.00"), gross, 14);
    assertThat(item.get("valorBrutoCentavos").asLong()).isEqualTo(cents(gross));
    assertThat(item.get("iofCentavos").asLong()).isEqualTo(cents(taxes.iof()));
    assertThat(item.get("irCentavos").asLong()).isEqualTo(cents(taxes.incomeTax()));
    assertThat(item.get("valorLiquidoCentavos").asLong()).isEqualTo(cents(taxes.net()));
    assertThat(item.get("iofCentavos").asLong()).isPositive();
  }

  @Test
  void theBankValueBecomesTheStartingPointFromItsDateOn() throws Exception {
    String id =
        api.createOk(actor.session(), title(broker, "lci", "cdi", "100", 100_000, "2035-03-01"));
    // Sábado, 10/03: o banco mostrou R$ 1.010,00.
    api.mark(actor.session(), id, "2035-03-10", 101_000);

    JsonNode before = valued("?data=2035-03-09").get("titulos").get(0);
    JsonNode after = valued("?data=2035-03-15").get("titulos").get(0);

    assertThat(before.get("valorBrutoCentavos").asLong())
        .isEqualTo(cents(new BigDecimal("1000").multiply(new BigDecimal("1.0005").pow(6))));
    // De 10/03 a 14/03: 12, 13 e 14 são dias úteis.
    assertThat(after.get("valorBrutoCentavos").asLong())
        .isEqualTo(cents(new BigDecimal("1010").multiply(new BigDecimal("1.0005").pow(3))));
  }

  @Test
  void aTitleAppliedAfterTheDateIsNotThereYetAndARedeemedOneShowsTheRedemption() throws Exception {
    api.createOk(actor.session(), title(broker, "lci", "cdi", "100", 100_000, "2035-03-20"));
    String redeemed =
        api.createOk(actor.session(), title(broker, "cdb", "cdi", "100", 50_000, "2035-03-01"));
    api.redeem(actor.session(), redeemed, "2035-03-12", 50_200);

    JsonNode titles = valued("?data=2035-03-15").get("titulos");

    // A lista vem por data de aplicação: o resgatado (01/03) antes do futuro (20/03).
    JsonNode gone = titles.get(0);
    JsonNode future = titles.get(1);
    assertThat(gone.get("valorBrutoCentavos").asLong()).isZero();
    assertThat(gone.get("valorLiquidoCentavos").asLong()).isZero();
    assertThat(gone.get("resgate").get("valorCentavos").asLong()).isEqualTo(50_200);
    assertThat(future.get("valorBrutoCentavos").isNull()).isTrue();
    assertThat(future.get("valorLiquidoCentavos").isNull()).isTrue();
  }

  @Test
  void anIpcaTitleCompoundsTheIndexAndTheSpread() throws Exception {
    api.createOk(actor.session(), title(broker, "lca", "ipca", "6", 100_000, "2035-03-01"));

    JsonNode item = valued("?data=2035-03-15").get("titulos").get(0);

    BigDecimal factor =
        FixedIncomeMath.ipcaFactor(
                Map.of(LocalDate.of(2035, 3, 1), new BigDecimal("1")),
                LocalDate.of(2035, 3, 1),
                LocalDate.of(2035, 3, 15))
            .multiply(FixedIncomeMath.prefixedFactor(new BigDecimal("6"), 10));
    assertThat(item.get("valorBrutoCentavos").asLong())
        .isEqualTo(cents(new BigDecimal("1000").multiply(factor)));
  }

  @Test
  void aFutureDateIsValuedAsToday() throws Exception {
    api.createOk(actor.session(), title(broker, "lci", "cdi", "100", 100_000, "2035-03-01"));

    long today =
        valued("?data=2035-03-31").get("titulos").get(0).get("valorBrutoCentavos").asLong();
    long future =
        valued("?data=2199-12-31").get("titulos").get(0).get("valorBrutoCentavos").asLong();

    assertThat(future).isEqualTo(today);
  }

  @Test
  void theListSaysUpToWhenThereAreIndices() throws Exception {
    JsonNode indices = CryptoApi.expect(api.indices(actor.session()), 200);
    JsonNode body = valued("?data=2035-03-15");

    assertThat(body.get("indices").get("cdiAte").asString()).isEqualTo("2035-03-30");
    assertThat(body.get("indices").get("ipcaAte").asString()).isEqualTo("2035-03-01");
    assertThat(indices.get("cdiAte").asString()).isEqualTo("2035-03-30");
  }

  @Test
  void theNetWorthAddsTheNetValueOfTheTitles() throws Exception {
    api.createOk(actor.session(), title(broker, "cdb", "prefixado", "12", 100_000, "2035-03-01"));
    long net =
        valued("?data=2035-03-15").get("titulos").get(0).get("valorLiquidoCentavos").asLong();

    JsonNode netWorth = CryptoApi.expect(base.netWorth(actor.session(), "?data=2035-03-15"), 200);

    assertThat(netWorth.get("rendaFixaCentavos").asLong()).isEqualTo(net);
    assertThat(netWorth.get("totalCentavos").asLong())
        .isEqualTo(netWorth.get("contasCentavos").asLong() + net);
  }

  @Test
  void anOldInvestmentFetchesTheMissingIndicesFromTheSource() throws Exception {
    // O atualizador baixa sempre contínuo desde o primeiro valor; aqui o primeiro passa a ser
    // 01/03/2035, como se o Banco Central nunca tivesse sido consultado antes disso.
    jdbc.sql("DELETE FROM indice WHERE serie = 'cdi' AND data < DATE '2035-03-01'").update();
    source.publish(
        IndexSeries.CDI, new IndexPoint(LocalDate.of(2035, 2, 26), new BigDecimal("0.04")));

    api.createOk(actor.session(), title(broker, "lci", "cdi", "100", 100_000, "2035-02-26"));

    assertThat(
            jdbc.sql("SELECT count(*) FROM indice WHERE serie = 'cdi' AND data = DATE '2035-02-26'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }
}
