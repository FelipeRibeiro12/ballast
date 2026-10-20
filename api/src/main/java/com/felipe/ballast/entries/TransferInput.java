package com.felipe.ballast.entries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Entrada já validada na borda. Id nulo quer dizer que o texto enviado não era um UUID; o serviço
// trata como conta que não existe. Título nulo vira o texto padrão no serviço.
record TransferInput(
    UUID fromAccountId,
    UUID toAccountId,
    BigDecimal amount,
    LocalDate date,
    String title,
    String description) {}
