package com.felipe.ballast.imports;

import java.math.BigDecimal;
import java.time.LocalDate;

// Uma transação do arquivo. Data e valor ficam nulos quando invalidReason está preenchido. O
// valor tem sinal: positivo é entrada, negativo é saída.
record OfxTransaction(
    int order,
    String type,
    LocalDate date,
    BigDecimal amount,
    String fitId,
    String name,
    String memo,
    String checkNumber,
    String invalidReason) {}
