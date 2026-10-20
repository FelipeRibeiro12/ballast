package com.felipe.ballast.recurrences;

import com.felipe.ballast.entries.EntryType;
import java.math.BigDecimal;
import java.util.UUID;

// Entrada já validada na borda. Id nulo quer dizer que o texto enviado não era um UUID; o serviço
// trata como conta ou categoria que não existe.
record RecurrenceInput(
    EntryType type,
    String title,
    String description,
    BigDecimal amount,
    UUID categoryId,
    UUID accountId,
    RecurrenceRule rule) {}
