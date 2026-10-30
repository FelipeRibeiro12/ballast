package com.felipe.ballast.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.felipe.ballast.FakeQuoteSource;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

// A tabela é global: o teste vive em 2037 e limpa tudo dali para a frente (ADR-077).
@IntegrationTest
class UsdcBrlUpdaterTest {

  private static final Instant NOW = Instant.parse("2037-06-10T15:00:00Z");

  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  @Autowired FakeQuoteSource source;
  @Autowired UsdcBrlUpdater updater;

  @BeforeEach
  void setUp() {
    clean();
    source.reset();
    clock.freeze(NOW);
  }

  @AfterEach
  void tearDown() {
    clean();
    source.reset();
    clock.reset();
  }

  private void clean() {
    jdbc.sql("DELETE FROM cotacao_usdc_brl WHERE momento >= TIMESTAMPTZ '2036-06-01 00:00:00Z'")
        .update();
  }

  private record Row(Instant moment, BigDecimal price, String source) {}

  private List<Row> rows() {
    return jdbc.sql(
            "SELECT momento, preco, fonte FROM cotacao_usdc_brl"
                + " WHERE momento >= TIMESTAMPTZ '2036-06-01 00:00:00Z' ORDER BY momento")
        .query(
            (rs, i) ->
                new Row(
                    rs.getObject("momento", OffsetDateTime.class).toInstant(),
                    rs.getBigDecimal("preco"),
                    rs.getString("fonte")))
        .list();
  }

  private void insert(Instant moment, String price) {
    jdbc.sql("INSERT INTO cotacao_usdc_brl (momento, preco) VALUES (:m, CAST(:p AS numeric))")
        .param("m", OffsetDateTime.ofInstant(moment, java.time.ZoneOffset.UTC))
        .param("p", price)
        .update();
  }

  @Test
  void storesTheReadingWithTheProviderTimeAndOnlyOncePerReading() {
    Instant at = NOW.minusSeconds(90);
    // Doze casas: o banco guarda oito, e o meio de baixo arredonda para o par (HALF_EVEN).
    source.publishUsdc(new UsdcQuote(new BigDecimal("5.123456785000"), at));

    updater.update();
    updater.update();

    assertThat(rows()).hasSize(1);
    assertThat(rows().getFirst().moment()).isEqualTo(at);
    assertThat(rows().getFirst().price()).isEqualByComparingTo("5.12345678");
    assertThat(rows().getFirst().source()).isEqualTo("coingecko");
  }

  @Test
  void aNewReadingAddsToTheSeries() {
    source.publishUsdc(new UsdcQuote(new BigDecimal("5.1"), NOW.minusSeconds(3600)));
    updater.update();
    source.publishUsdc(new UsdcQuote(new BigDecimal("5.2"), NOW.minusSeconds(60)));
    updater.update();

    assertThat(rows())
        .extracting(Row::moment)
        .containsExactly(NOW.minusSeconds(3600), NOW.minusSeconds(60));
  }

  @Test
  void aReadingFromTheFutureOrOutOfRangeNeverGetsStored() {
    source.publishUsdc(new UsdcQuote(new BigDecimal("5"), NOW.plusSeconds(3600)));
    updater.update();
    source.publishUsdc(new UsdcQuote(new BigDecimal("0.000000001"), NOW.minusSeconds(60)));
    updater.update();
    source.publishUsdc(new UsdcQuote(new BigDecimal("1000000000000"), NOW.minusSeconds(60)));
    updater.update();

    assertThat(rows()).isEmpty();
  }

  @Test
  void whenTheProviderIsDownTheRunDoesNotThrowAndTheLastReadingStays() {
    source.publishUsdc(new UsdcQuote(new BigDecimal("5.1"), NOW.minusSeconds(3600)));
    updater.update();
    source.goDown();

    assertThatCode(updater::update).doesNotThrowAnyException();
    assertThatCode(updater::backfill).doesNotThrowAnyException();

    assertThat(rows()).hasSize(1);
  }

  @Test
  void backfillBringsTheWholeWindowOnceWhenThereIsNothingOlderThan300Days() {
    Instant first = NOW.minus(java.time.Duration.ofDays(364));
    source.publishUsdcHistory(
        List.of(
            new UsdcQuote(new BigDecimal("5.0"), first),
            new UsdcQuote(new BigDecimal("0"), first.plusSeconds(86_400)),
            new UsdcQuote(new BigDecimal("5.5"), first.plusSeconds(2 * 86_400L))));

    updater.backfill();
    updater.backfill();

    assertThat(rows()).extracting(Row::moment).containsExactly(first, first.plusSeconds(172_800));
    assertThat(source.usdcHistoryRequests()).hasSize(1);
    assertThat(source.usdcHistoryRequests().getFirst().from()).isEqualTo(first);
    assertThat(source.usdcHistoryRequests().getFirst().to()).isEqualTo(NOW);
  }

  @Test
  void backfillKeepsWhatIsAlreadyThereAtTheSameMoment() {
    Instant first = NOW.minus(java.time.Duration.ofDays(364));
    Instant yesterday = NOW.minus(java.time.Duration.ofDays(1));
    insert(yesterday, "9");
    source.publishUsdcHistory(
        List.of(
            new UsdcQuote(new BigDecimal("5.0"), first),
            new UsdcQuote(new BigDecimal("5.5"), yesterday)));

    updater.backfill();

    assertThat(rows())
        .extracting(Row::price)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(new BigDecimal("5.0"), new BigDecimal("9"));
  }

  @Test
  void backfillDoesNotCallTheProviderWhenTheHistoryAlreadyReachesBack() {
    insert(NOW.minus(java.time.Duration.ofDays(301)), "5");

    updater.backfill();

    assertThat(source.usdcHistoryRequests()).isEmpty();
  }
}
