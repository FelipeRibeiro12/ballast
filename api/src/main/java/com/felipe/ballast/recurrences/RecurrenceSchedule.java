package com.felipe.ballast.recurrences;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

// Motor de datas da regra (ADR-026), sem banco e sem Spring. A série é indexada por período
// (dia, semana, mês ou ano contado a partir do início, de intervalo em intervalo), e cada período
// dá uma data. A data sempre cresce com o período, então o laço termina ao passar de "to".
final class RecurrenceSchedule {

  private RecurrenceSchedule() {}

  // Datas da série entre from e to, pontas incluídas, em ordem. A data que cai antes do início
  // (dia do mês anterior ao do início, por exemplo) não é ocorrência e não conta no
  // ocorrencias_max. O max conta desde o início, também as datas anteriores a "from".
  static List<LocalDate> occurrences(RecurrenceRule rule, LocalDate from, LocalDate to) {
    List<LocalDate> result = new ArrayList<>();
    long counted = 0;
    for (long period = 0; ; period++) {
      LocalDate date = dateOf(rule, period);
      if (date.isAfter(to) || (rule.end() != null && date.isAfter(rule.end()))) {
        break;
      }
      if (date.isBefore(rule.start())) {
        continue;
      }
      if (rule.maxOccurrences() != null && counted >= rule.maxOccurrences()) {
        break;
      }
      counted++;
      if (!date.isBefore(from)) {
        result.add(date);
      }
    }
    return result;
  }

  private static LocalDate dateOf(RecurrenceRule rule, long period) {
    long step = period * rule.interval();
    return switch (rule.frequency()) {
      case DAILY -> rule.start().plusDays(step);
      case WEEKLY -> weekOf(rule.start()).plusDays(step * 7 + rule.dayOfWeek());
      case MONTHLY -> clamp(YearMonth.from(rule.start()).plusMonths(step), rule.dayOfMonth());
      // O dia vem sempre do início: 29/2 volta a ser 29/2 no próximo ano bissexto.
      case ANNUAL ->
          clamp(
              YearMonth.of(Math.toIntExact(rule.start().getYear() + step), rule.start().getMonth()),
              rule.start().getDayOfMonth());
    };
  }

  // Semana de domingo a sábado, a mesma ordem do dia_da_semana do banco (0 é domingo).
  private static LocalDate weekOf(LocalDate date) {
    return date.minusDays(date.getDayOfWeek().getValue() % 7);
  }

  // Dia maior que o último do mês cai no último dia.
  private static LocalDate clamp(YearMonth month, int day) {
    return month.atDay(Math.min(day, month.lengthOfMonth()));
  }
}
