package com.felipe.ballast.accounts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// inInvestments só é verdadeiro no cofrinho que o usuário marcou (ADR-076).
// cdiPercent é nulo fora do cofrinho e no cofrinho sem percentual.
// permittedCategories é nulo fora de conta de benefício e vazio quando tudo é permitido.
// Linha da consulta com saldo. Valores em BigDecimal com escala 2; a conversão para centavos
// acontece só na saída (AccountResponse).
record AccountSummary(
    UUID id,
    String name,
    AccountType type,
    String currency,
    BigDecimal initialBalance,
    BigDecimal balance,
    boolean archived,
    Instant createdAt,
    CardSummary card,
    List<UUID> permittedCategories,
    BigDecimal cdiPercent,
    boolean inInvestments) {

  // Nulo fora de conta de cartão.
  record CardSummary(BigDecimal limit, int closingDay, int dueDay, UUID paymentAccountId) {}
}
