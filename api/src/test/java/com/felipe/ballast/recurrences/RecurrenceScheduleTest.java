package com.felipe.ballast.recurrences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// Motor puro: sem banco e sem Spring. Cada teste monta a regra e confere as datas em ordem.
class RecurrenceScheduleTest {

  private static final LocalDate FAR = LocalDate.of(2199, 12, 31);

  private static LocalDate d(String iso) {
    return LocalDate.parse(iso);
  }

  private static List<String> dates(RecurrenceRule rule, String from, String to) {
    return RecurrenceSchedule.occurrences(rule, d(from), d(to)).stream()
        .map(LocalDate::toString)
        .toList();
  }

  private static RecurrenceRule monthly(int day, int interval, String start) {
    return new RecurrenceRule(
        RecurrenceFrequency.MONTHLY, interval, day, null, d(start), null, null);
  }

  private static RecurrenceRule weekly(int dayOfWeek, int interval, String start) {
    return new RecurrenceRule(
        RecurrenceFrequency.WEEKLY, interval, null, dayOfWeek, d(start), null, null);
  }

  private static RecurrenceRule daily(int interval, String start) {
    return new RecurrenceRule(
        RecurrenceFrequency.DAILY, interval, null, null, d(start), null, null);
  }

  private static RecurrenceRule annual(int interval, String start) {
    return new RecurrenceRule(
        RecurrenceFrequency.ANNUAL, interval, null, null, d(start), null, null);
  }

  // Mensal

  @Test
  void monthlyDay31FallsOnTheLastDayOfShortMonths() {
    assertThat(dates(monthly(31, 1, "2026-01-01"), "2026-01-01", "2026-04-30"))
        .containsExactly("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30");
  }

  @Test
  void monthlyDay29To31InFebruaryOfALeapYearFallsOn29() {
    assertThat(dates(monthly(31, 1, "2028-01-01"), "2028-01-01", "2028-03-31"))
        .containsExactly("2028-01-31", "2028-02-29", "2028-03-31");
    assertThat(dates(monthly(29, 1, "2028-02-01"), "2028-02-01", "2028-02-29"))
        .containsExactly("2028-02-29");
  }

  @Test
  void monthlyDay30InFebruaryFallsOnTheLastDay() {
    assertThat(dates(monthly(30, 1, "2026-01-01"), "2026-01-01", "2026-03-31"))
        .containsExactly("2026-01-30", "2026-02-28", "2026-03-30");
    assertThat(dates(monthly(30, 1, "2028-01-01"), "2028-02-01", "2028-02-29"))
        .containsExactly("2028-02-29");
  }

  @Test
  void clampingDoesNotDriftTheDayAfterAShortMonth() {
    assertThat(dates(monthly(31, 1, "2026-01-01"), "2026-03-01", "2026-03-31"))
        .containsExactly("2026-03-31");
  }

  @Test
  void monthlyEveryTwoMonthsCountsFromTheStartMonth() {
    assertThat(dates(monthly(15, 2, "2026-01-01"), "2026-01-01", "2026-08-31"))
        .containsExactly("2026-01-15", "2026-03-15", "2026-05-15", "2026-07-15");
  }

  @Test
  void monthlyEveryThreeMonths() {
    assertThat(dates(monthly(15, 3, "2026-01-01"), "2026-01-01", "2026-12-31"))
        .containsExactly("2026-01-15", "2026-04-15", "2026-07-15", "2026-10-15");
  }

  @Test
  void monthlyEveryTwoMonthsClampsEachPeriod() {
    assertThat(dates(monthly(31, 2, "2026-01-01"), "2026-01-01", "2026-07-31"))
        .containsExactly("2026-01-31", "2026-03-31", "2026-05-31", "2026-07-31");
    assertThat(dates(monthly(31, 1, "2026-02-01"), "2026-02-01", "2026-02-28"))
        .containsExactly("2026-02-28");
  }

  @Test
  void monthlyStartingMidMonthWithAnEarlierDayStartsNextMonth() {
    assertThat(dates(monthly(5, 1, "2026-01-20"), "2026-01-01", "2026-03-31"))
        .containsExactly("2026-02-05", "2026-03-05");
  }

  @Test
  void monthlyEveryTwoMonthsStartingAfterTheDayKeepsTheStartPeriodCadence() {
    // Janeiro é o período do início: a data de janeiro já passou, a próxima é a de março.
    assertThat(dates(monthly(5, 2, "2026-01-20"), "2026-01-01", "2026-06-30"))
        .containsExactly("2026-03-05", "2026-05-05");
  }

  @Test
  void monthlyOccurrenceOnTheStartDateIsIncluded() {
    assertThat(dates(monthly(20, 1, "2026-01-20"), "2026-01-01", "2026-02-28"))
        .containsExactly("2026-01-20", "2026-02-20");
  }

  @Test
  void monthlyCrossesTheYearBoundary() {
    assertThat(dates(monthly(10, 1, "2026-11-10"), "2026-11-01", "2027-02-28"))
        .containsExactly("2026-11-10", "2026-12-10", "2027-01-10", "2027-02-10");
  }

  // Semanal: 0 é domingo e 6 é sábado, e o DayOfWeek do java.time vai de 1 (segunda) a 7.

  @ParameterizedTest
  @CsvSource({
    "0, 2026-10-25, SUNDAY",
    "1, 2026-10-19, MONDAY",
    "2, 2026-10-20, TUESDAY",
    "3, 2026-10-21, WEDNESDAY",
    "4, 2026-10-22, THURSDAY",
    "5, 2026-10-23, FRIDAY",
    "6, 2026-10-24, SATURDAY"
  })
  void weeklyMapsZeroToSundayAndSixToSaturday(int day, String first, DayOfWeek expected) {
    // 2026-10-19 é segunda-feira.
    List<LocalDate> result =
        RecurrenceSchedule.occurrences(
            weekly(day, 1, "2026-10-19"), d("2026-10-19"), d("2026-11-15"));

    assertThat(result.getFirst()).isEqualTo(d(first));
    assertThat(result).hasSize(4).allMatch(date -> date.getDayOfWeek() == expected);
    assertThat(result.get(1)).isEqualTo(d(first).plusDays(7));
  }

  @Test
  void weeklyEveryTwoWeeks() {
    assertThat(dates(weekly(1, 2, "2026-10-19"), "2026-10-19", "2026-11-30"))
        .containsExactly("2026-10-19", "2026-11-02", "2026-11-16", "2026-11-30");
  }

  @Test
  void weeklyStartingMidWeekSkipsTheDayThatAlreadyPassed() {
    // 2026-10-21 é quarta-feira.
    assertThat(dates(weekly(1, 1, "2026-10-21"), "2026-10-21", "2026-11-09"))
        .containsExactly("2026-10-26", "2026-11-02", "2026-11-09");
  }

  @Test
  void weeklyEveryTwoWeeksStartingMidWeekKeepsTheStartWeekCadence() {
    // Semana de domingo a sábado: a segunda de 19/10 é da semana do início e já passou, então a
    // primeira é a da semana duas à frente.
    assertThat(dates(weekly(1, 2, "2026-10-21"), "2026-10-21", "2026-11-30"))
        .containsExactly("2026-11-02", "2026-11-16", "2026-11-30");
    assertThat(dates(weekly(0, 2, "2026-10-21"), "2026-10-21", "2026-11-30"))
        .containsExactly("2026-11-01", "2026-11-15", "2026-11-29");
  }

  @Test
  void weeklyCrossesTheYearBoundary() {
    // 2026-12-28 é segunda-feira.
    assertThat(dates(weekly(1, 1, "2026-12-28"), "2026-12-28", "2027-01-11"))
        .containsExactly("2026-12-28", "2027-01-04", "2027-01-11");
  }

  // Anual

  @Test
  void annualFromFeb29FallsOnFeb28InCommonYears() {
    assertThat(dates(annual(1, "2028-02-29"), "2028-01-01", "2032-12-31"))
        .containsExactly("2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29");
  }

  @Test
  void annualEveryTwoYears() {
    assertThat(dates(annual(2, "2026-05-10"), "2026-01-01", "2031-12-31"))
        .containsExactly("2026-05-10", "2028-05-10", "2030-05-10");
  }

  // Diária

  @Test
  void dailyEveryDay() {
    assertThat(dates(daily(1, "2026-12-30"), "2026-12-30", "2027-01-02"))
        .containsExactly("2026-12-30", "2026-12-31", "2027-01-01", "2027-01-02");
  }

  @Test
  void dailyEverySevenDaysWithFromAfterTheStartKeepsTheCadence() {
    assertThat(dates(daily(7, "2026-10-01"), "2026-10-10", "2026-11-05"))
        .containsExactly("2026-10-15", "2026-10-22", "2026-10-29", "2026-11-05");
  }

  @Test
  void dailyEveryThreeDaysWithFromAfterTheStart() {
    assertThat(dates(daily(3, "2026-10-01"), "2026-10-10", "2026-10-20"))
        .containsExactly("2026-10-10", "2026-10-13", "2026-10-16", "2026-10-19");
  }

  @Test
  void aTwelveMonthDailySeriesHasAbout366Dates() {
    assertThat(
            RecurrenceSchedule.occurrences(
                daily(1, "2026-10-19"), d("2026-10-19"), d("2027-10-19")))
        .hasSize(366);
  }

  // Fim e ocorrencias_max

  @Test
  void endDateIsIncludedWhenItIsAnOccurrence() {
    RecurrenceRule rule =
        new RecurrenceRule(
            RecurrenceFrequency.DAILY, 1, null, null, d("2026-10-01"), d("2026-10-05"), null);

    assertThat(RecurrenceSchedule.occurrences(rule, d("2026-10-01"), FAR))
        .extracting(LocalDate::toString)
        .containsExactly("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05");
  }

  @Test
  void endDateBetweenOccurrencesCutsTheSeriesBefore() {
    RecurrenceRule rule =
        new RecurrenceRule(
            RecurrenceFrequency.MONTHLY, 1, 15, null, d("2026-01-01"), d("2026-03-14"), null);

    assertThat(RecurrenceSchedule.occurrences(rule, d("2026-01-01"), FAR))
        .extracting(LocalDate::toString)
        .containsExactly("2026-01-15", "2026-02-15");
  }

  @Test
  void endEqualToStartIsASingleOccurrenceWhenTheStartIsOne() {
    RecurrenceRule rule =
        new RecurrenceRule(
            RecurrenceFrequency.DAILY, 1, null, null, d("2026-10-01"), d("2026-10-01"), null);

    assertThat(RecurrenceSchedule.occurrences(rule, d("2026-10-01"), FAR))
        .containsExactly(d("2026-10-01"));
  }

  @Test
  void maxOccurrencesStopsTheSeries() {
    RecurrenceRule rule =
        new RecurrenceRule(RecurrenceFrequency.DAILY, 1, null, null, d("2026-10-01"), null, 3);

    assertThat(RecurrenceSchedule.occurrences(rule, d("2026-10-01"), FAR))
        .extracting(LocalDate::toString)
        .containsExactly("2026-10-01", "2026-10-02", "2026-10-03");
  }

  @Test
  void maxOccurrencesCountsFromTheStartEvenWhenFromIsLater() {
    RecurrenceRule rule =
        new RecurrenceRule(RecurrenceFrequency.DAILY, 1, null, null, d("2026-10-01"), null, 5);

    assertThat(RecurrenceSchedule.occurrences(rule, d("2026-10-04"), FAR))
        .extracting(LocalDate::toString)
        .containsExactly("2026-10-04", "2026-10-05");
    assertThat(RecurrenceSchedule.occurrences(rule, d("2026-10-06"), FAR)).isEmpty();
  }

  @Test
  void maxOccurrencesDoesNotCountTheDayThatFellBeforeTheStart() {
    RecurrenceRule rule =
        new RecurrenceRule(RecurrenceFrequency.MONTHLY, 1, 5, null, d("2026-01-20"), null, 2);

    assertThat(RecurrenceSchedule.occurrences(rule, d("2026-01-01"), FAR))
        .extracting(LocalDate::toString)
        .containsExactly("2026-02-05", "2026-03-05");
  }

  @Test
  void maxOccurrencesWithTheTopOfTheRangeStillStops() {
    RecurrenceRule rule =
        new RecurrenceRule(RecurrenceFrequency.DAILY, 1, null, null, d("2000-01-01"), null, 10_000);

    List<LocalDate> result = RecurrenceSchedule.occurrences(rule, d("2000-01-01"), FAR);

    assertThat(result).hasSize(10_000);
    assertThat(result.getLast()).isEqualTo(d("2000-01-01").plusDays(9_999));
  }

  // Limites do intervalo pedido

  @Test
  void anOldSeriesOnlyReturnsTheRequestedWindow() {
    assertThat(
            RecurrenceSchedule.occurrences(
                daily(1, "1900-01-01"), d("2026-10-19"), d("2027-10-19")))
        .hasSize(366)
        .first()
        .isEqualTo(d("2026-10-19"));
  }

  @Test
  void toBeforeTheStartIsEmpty() {
    assertThat(dates(monthly(5, 1, "2026-10-01"), "2026-01-01", "2026-09-30")).isEmpty();
  }

  @Test
  void fromAfterToIsEmpty() {
    assertThat(dates(daily(1, "2026-10-01"), "2026-10-10", "2026-10-09")).isEmpty();
  }

  @Test
  void bothEndsOfTheWindowAreIncluded() {
    assertThat(dates(monthly(19, 1, "2026-01-01"), "2026-10-19", "2027-10-19"))
        .hasSize(13)
        .startsWith("2026-10-19")
        .endsWith("2027-10-19");
  }

  @Test
  void intervalBelowOneIsRefused() {
    assertThatThrownBy(() -> daily(0, "2026-10-01")).isInstanceOf(IllegalArgumentException.class);
  }

  // Divisão: o período é o mês e cada parcela tem o seu dia

  private static List<String> parcels(
      RecurrenceRule rule, List<Integer> days, String from, String to) {
    return RecurrenceSchedule.parcels(rule, days, d(from), d(to)).stream()
        .map(p -> p.date() + "#" + p.order())
        .toList();
  }

  @Test
  void parcelsGiveOneDatePerDayInEachMonth() {
    assertThat(parcels(monthly(15, 1, "2026-01-01"), List.of(15, 30), "2026-01-01", "2026-02-28"))
        .containsExactly("2026-01-15#1", "2026-01-30#2", "2026-02-15#1", "2026-02-28#2");
  }

  @Test
  void parcelsOfDays30And31BothFallOnTheLastDayOfFebruary() {
    assertThat(parcels(monthly(30, 1, "2026-02-01"), List.of(30, 31), "2026-02-01", "2026-02-28"))
        .containsExactly("2026-02-28#1", "2026-02-28#2");
    assertThat(parcels(monthly(30, 1, "2028-02-01"), List.of(30, 31), "2028-02-01", "2028-02-29"))
        .containsExactly("2028-02-29#1", "2028-02-29#2");
  }

  @Test
  void parcelsFollowTheOrderOfTheArrayEvenWhenTheDaysAreNotAscending() {
    assertThat(parcels(monthly(20, 1, "2026-01-01"), List.of(20, 5), "2026-01-01", "2026-01-31"))
        .containsExactly("2026-01-20#1", "2026-01-05#2");
  }

  @Test
  void maxOccurrencesCountsMonthsNotParcels() {
    RecurrenceRule rule =
        new RecurrenceRule(RecurrenceFrequency.MONTHLY, 1, 15, null, d("2026-01-01"), null, 3);

    assertThat(parcels(rule, List.of(15, 30), "2026-01-01", "2030-01-01"))
        .containsExactly(
            "2026-01-15#1",
            "2026-01-30#2",
            "2026-02-15#1",
            "2026-02-28#2",
            "2026-03-15#1",
            "2026-03-30#2");
  }

  @Test
  void endFiltersByParcelDate() {
    RecurrenceRule rule =
        new RecurrenceRule(
            RecurrenceFrequency.MONTHLY, 1, 15, null, d("2026-01-01"), d("2026-02-20"), null);

    assertThat(parcels(rule, List.of(15, 30), "2026-01-01", "2030-01-01"))
        .containsExactly("2026-01-15#1", "2026-01-30#2", "2026-02-15#1");
  }

  @Test
  void parcelBeforeFromIsDroppedAndTheOneAfterStays() {
    assertThat(parcels(monthly(15, 1, "2026-01-01"), List.of(15, 30), "2026-10-20", "2026-10-31"))
        .containsExactly("2026-10-30#2");
  }

  @Test
  void parcelBeforeTheStartDoesNotCountAsAnOccurrence() {
    RecurrenceRule rule =
        new RecurrenceRule(RecurrenceFrequency.MONTHLY, 1, 5, null, d("2026-01-20"), null, 1);

    assertThat(parcels(rule, List.of(5, 25), "2026-01-01", "2026-12-31"))
        .containsExactly("2026-01-25#2");
  }

  @Test
  void parcelsRespectTheIntervalBetweenMonths() {
    assertThat(parcels(monthly(5, 2, "2026-01-01"), List.of(5, 20), "2026-01-01", "2026-06-30"))
        .containsExactly(
            "2026-01-05#1",
            "2026-01-20#2",
            "2026-03-05#1",
            "2026-03-20#2",
            "2026-05-05#1",
            "2026-05-20#2");
  }
}
