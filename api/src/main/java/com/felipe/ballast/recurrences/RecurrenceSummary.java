package com.felipe.ballast.recurrences;

import com.felipe.ballast.entries.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Linha da consulta. O valor fica em BigDecimal com escala 2; a conversão para centavos acontece
// só na saída (RecurrenceResponse). nextOccurrence é nula sem previsto de hoje em diante e
// division, sem divisão.
record RecurrenceSummary(
    UUID id,
    EntryType type,
    String title,
    String description,
    BigDecimal amount,
    UUID categoryId,
    UUID accountId,
    boolean active,
    Instant createdAt,
    RecurrenceRule rule,
    LocalDate nextOccurrence,
    Division division) {

  RecurrenceSummary withDivision(Division newDivision) {
    return new RecurrenceSummary(
        id,
        type,
        title,
        description,
        amount,
        categoryId,
        accountId,
        active,
        createdAt,
        rule,
        nextOccurrence,
        newDivision);
  }
}
