package com.felipe.ballast.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// O que a consulta devolve, ainda em BigDecimal com escala 2. A conversão para centavos acontece
// só na saída (ProjectionResponse).
record ProjectionRows(
    long accountCount,
    BigDecimal initialBalance,
    BigDecimal balanceBeforeToday,
    BigDecimal overdue,
    BigDecimal realized,
    List<DayRow> days) {

  record DayRow(LocalDate date, BigDecimal inflow, BigDecimal outflow) {}
}
