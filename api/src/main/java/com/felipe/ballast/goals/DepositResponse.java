package com.felipe.ballast.goals;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "AporteMeta")
record DepositResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Positivo guardou, negativo retirou",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long valorCentavos,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        LocalDate data,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            format = "uuid",
            description = "Transferência ligada ao aporte; nulo quando não há")
        UUID transferenciaId) {

  static DepositResponse from(GoalQueries.Deposit deposit) {
    return new DepositResponse(
        deposit.id(),
        deposit.value().movePointRight(2).longValueExact(),
        deposit.date(),
        deposit.transferId());
  }
}
