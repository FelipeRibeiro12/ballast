package com.felipe.ballast.entries;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Linha da consulta. O valor fica em BigDecimal com escala 2; a conversão para centavos
// acontece só na saída (EntryResponse). transferId e direction são nulos fora de transferência;
// templateId e parcelOrder são nulos no avulso e na ocorrência sem divisão. purchaseId, installment
// e installmentTotal (compra parcelada, ADR-032) são nulos fora de parcela.
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
    Instant createdAt,
    UUID transferId,
    EntryDirection direction,
    UUID templateId,
    Integer parcelOrder,
    UUID purchaseId,
    Integer installment,
    Integer installmentTotal,
    UUID thirdPartyId) {}
