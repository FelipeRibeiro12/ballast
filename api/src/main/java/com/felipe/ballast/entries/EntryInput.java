package com.felipe.ballast.entries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Entrada já validada na borda. Id nulo quer dizer que o texto enviado não era um UUID; o serviço
// trata como conta ou categoria que não existe.
record EntryInput(
    EntryType type,
    String title,
    String description,
    BigDecimal amount,
    UUID categoryId,
    UUID accountId,
    LocalDate date) {}
