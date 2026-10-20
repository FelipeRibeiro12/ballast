package com.felipe.ballast.entries;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Linha da consulta. Valor, data, título e descrição vêm da perna de saída: as duas pernas
// guardam o mesmo (ADR-025). criadaEm é o da perna, porque a ligação não tem coluna de criação.
record TransferSummary(
    UUID id,
    UUID fromAccountId,
    UUID toAccountId,
    BigDecimal amount,
    LocalDate date,
    String title,
    String description,
    UUID outEntryId,
    UUID inEntryId,
    Instant createdAt) {}
