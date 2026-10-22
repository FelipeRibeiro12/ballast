package com.felipe.ballast.fixedincome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.felipe.ballast.FakeIndexSource;
import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

// indice é global e o banco é compartilhado: este teste vive em 2041, longe dos outros.
@IntegrationTest
class IndexUpdaterTest {

  @Autowired IndexUpdater updater;
  @Autowired FakeIndexSource source;
  @Autowired TestClock clock;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void setUp() {
    jdbc.sql("DELETE FROM indice WHERE data >= DATE '2030-01-01'").update();
    source.reset();
    clock.freeze(Instant.parse("2041-03-10T15:00:00Z"));
  }

  @AfterEach
  void tearDown() {
    source.reset();
    clock.reset();
    jdbc.sql("DELETE FROM indice WHERE data >= DATE '2030-01-01'").update();
  }

  private long stored(IndexSeries series) {
    return jdbc.sql(
            "SELECT count(*) FROM indice WHERE serie = :serie AND data >= DATE '2030-01-01'")
        .param("serie", series.value())
        .query(Long.class)
        .single();
  }

  @Test
  void storesWhatTheSourcePublishedAndDoesNotAskForItAgain() {
    source.publish(IndexSeries.CDI, point("2041-03-01", "0.05"));
    source.publish(IndexSeries.CDI, point("2041-03-02", "0.04"));
    source.publish(IndexSeries.IPCA, point("2041-03-01", "0.45"));

    updater.updateFrom(LocalDate.of(2041, 3, 1));

    assertThat(stored(IndexSeries.CDI)).isEqualTo(2);
    assertThat(stored(IndexSeries.IPCA)).isEqualTo(1);
    source.reset();
    updater.updateFrom(LocalDate.of(2041, 3, 1));
    assertThat(source.requests())
        .containsExactly("cdi 2041-03-03 2041-03-10", "ipca 2041-03-02 2041-03-10");
  }

  @Test
  void aWeekendOrTheIpcaOfTheMonthBeforeTheFirstValueIsNotAskedAgain() {
    source.publish(IndexSeries.CDI, point("2041-03-04", "0.05"));
    source.publish(IndexSeries.IPCA, point("2041-03-01", "0.45"));
    // 02/03/2041 é sábado: o primeiro CDI é segunda, 04/03.
    updater.updateFrom(LocalDate.of(2041, 3, 2));
    source.reset();

    updater.updateFrom(LocalDate.of(2041, 3, 2));

    assertThat(source.requests()).noneMatch(request -> request.contains("2041-03-02 2041-03-03"));
    assertThat(source.requests()).noneMatch(request -> request.endsWith("2041-02-28"));
  }

  private static IndexPoint point(String date, String value) {
    return new IndexPoint(LocalDate.parse(date), new BigDecimal(value));
  }

  @Test
  void fillsTheGapBeforeTheFirstStoredValueWhenAnOlderInvestmentAppears() {
    source.publish(
        IndexSeries.CDI, new IndexPoint(LocalDate.of(2041, 3, 1), new BigDecimal("0.05")));
    updater.updateFrom(LocalDate.of(2041, 3, 1));
    source.publish(
        IndexSeries.CDI, new IndexPoint(LocalDate.of(2040, 6, 3), new BigDecimal("0.03")));

    updater.updateFrom(LocalDate.of(2040, 6, 1));

    assertThat(stored(IndexSeries.CDI)).isEqualTo(2);
  }

  @Test
  void asksInChunksOfAtMostFiveYears() {
    updater.updateFrom(LocalDate.of(2030, 1, 1));

    assertThat(source.requests())
        .contains(
            "cdi 2030-01-01 2034-12-31", "cdi 2035-01-01 2039-12-31", "cdi 2040-01-01 2041-03-10");
  }

  @Test
  void theSourceBeingDownNeverEscapesAndKeepsWhatWasStored() {
    source.publish(
        IndexSeries.CDI, new IndexPoint(LocalDate.of(2041, 3, 1), new BigDecimal("0.05")));
    updater.updateFrom(LocalDate.of(2041, 3, 1));
    source.goDown();
    clock.freeze(Instant.parse("2041-03-20T15:00:00Z"));

    assertThatCode(() -> updater.updateFrom(LocalDate.of(2041, 3, 1))).doesNotThrowAnyException();
    assertThat(stored(IndexSeries.CDI)).isEqualTo(1);
  }
}
