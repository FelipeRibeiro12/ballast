package com.felipe.ballast.entries;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Linha da consulta. O valor fica em BigDecimal com escala 2; a conversão para centavos
// acontece só na saída (EntryResponse).
record EntrySummary(
    UUID id,
    EntryType type,
    String title,
    String description,
    BigDecimal amount,
    UUID categoryId,
    UUID accountId,
    LocalDate competenceDate,
    LocalDate cashDate,
    String status,
    Instant createdAt) {}
