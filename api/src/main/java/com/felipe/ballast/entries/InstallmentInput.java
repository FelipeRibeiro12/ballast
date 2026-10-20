package com.felipe.ballast.entries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Compra parcelada já validada na borda. Id nulo quer dizer que o texto enviado não era um UUID; o
// serviço trata como conta ou categoria que não existe.
record InstallmentInput(
    String title,
    String description,
    BigDecimal total,
    int count,
    UUID categoryId,
    UUID accountId,
    LocalDate date) {}
