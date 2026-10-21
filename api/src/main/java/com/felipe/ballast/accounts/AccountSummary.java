package com.felipe.ballast.accounts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    List<UUID> permittedCategories) {

  // Nulo fora de conta de cartão.
  record CardSummary(BigDecimal limit, int closingDay, int dueDay, UUID paymentAccountId) {}
}
