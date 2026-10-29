package com.felipe.ballast.crypto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Schema(name = "MovimentoCriptoCustoRequest")
public record MovementCostRequest(
    @JsonProperty("custoUsdCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "O que pagou ou recebeu, em centavos de dólar",
            minimum = "1",
            maximum = "180143985094819")
        @NotNull(message = MovementRequest.COST_MESSAGE)
        @Min(value = 1, message = MovementRequest.COST_MESSAGE)
        @Max(value = MovementRequest.MAX_COST_USD_CENTS, message = MovementRequest.COST_MESSAGE)
        Long costCents) {

  BigDecimal costUsd() {
    return BigDecimal.valueOf(costCents, 2);
  }
}
