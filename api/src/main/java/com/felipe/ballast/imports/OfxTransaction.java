package com.felipe.ballast.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

// Uma transação do arquivo. Data e valor ficam nulos quando invalidReason está preenchido. O
// valor tem sinal: positivo é entrada, negativo é saída. raw guarda os campos lidos, como texto,
// para a coluna bruta da linha.
record OfxTransaction(
    int order,
    String type,
    LocalDate date,
    BigDecimal amount,
    String fitId,
    String name,
    String memo,
    String checkNumber,
    String invalidReason,
    Map<String, String> raw) {}
