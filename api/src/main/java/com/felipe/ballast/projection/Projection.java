package com.felipe.ballast.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Resultado do cálculo, em BigDecimal. Saídas (outflow) são positivas; o sinal está no saldo.
record Projection(
    LocalDate start,
    LocalDate end,
    UUID accountId,
    BigDecimal currentBalance,
    BigDecimal overdue,
    Point lowest,
    List<Day> days) {

  record Day(LocalDate date, BigDecimal inflow, BigDecimal outflow, BigDecimal balance) {}

  record Point(LocalDate date, BigDecimal balance) {}
}
