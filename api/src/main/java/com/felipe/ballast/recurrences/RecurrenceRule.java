package com.felipe.ballast.recurrences;

import java.time.LocalDate;

// Regra já validada na borda e conferida pelos CHECKs da V8. dayOfWeek segue o banco: 0 é
// domingo e 6 é sábado, diferente do DayOfWeek do java.time. Campos que a frequência não usa
// ficam nulos.
record RecurrenceRule(
    RecurrenceFrequency frequency,
    int interval,
    Integer dayOfMonth,
    Integer dayOfWeek,
    LocalDate start,
    LocalDate end,
    Integer maxOccurrences) {

  // Intervalo menor que 1 pararia o avanço das datas e a geração nunca chegaria ao fim.
  RecurrenceRule {
    if (interval < 1) {
      throw new IllegalArgumentException("intervalo deve ser maior que zero");
    }
    if (frequency == RecurrenceFrequency.MONTHLY && dayOfMonth == null
        || frequency == RecurrenceFrequency.WEEKLY && dayOfWeek == null) {
      throw new IllegalArgumentException("regra sem o dia que a frequência exige");
    }
  }
}
