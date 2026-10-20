package com.felipe.ballast.entries;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// A compra vista pelas suas parcelas que ainda existem. Os campos de texto, a categoria e a conta
// vêm da primeira parcela. installments é o total que a compra teve ao nascer, e total é a soma das
// parcelas de hoje: muda quando uma parcela é editada ou apagada.
record InstallmentPurchase(
    UUID id,
    String title,
    String description,
    BigDecimal total,
    int installments,
    UUID categoryId,
    UUID accountId,
    LocalDate purchaseDate,
    Instant createdAt,
    List<Item> items) {

  record Item(UUID entryId, int number, BigDecimal amount, LocalDate cashDate) {}
}
