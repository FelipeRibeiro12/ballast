package com.felipe.ballast.recurrences;

import java.math.BigDecimal;

// Uma parcela já validada. Só um entre percent e fixedAmount vem preenchido, conforme o modo.
// percent está em pontos percentuais (40.00 é 40%), a escala do numeric(5,2) do banco.
record DivisionPart(int order, int dayOfMonth, BigDecimal percent, BigDecimal fixedAmount) {}
