package com.felipe.ballast.crypto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "MovimentoCripto")
public record MovementResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ativoId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MovementType tipo,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Em texto, sem zero à direita",
            example = "0.12345678")
        String quantidade,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        LocalDate data,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "1",
            maximum = "9007199254740991")
        long custoCentavos) {

  // Única conversão para centavos, na borda de saída; o CHECK da V13 já garante a faixa.
  static MovementResponse from(CryptoQueries.Movement movement) {
    return new MovementResponse(
        movement.id(),
        movement.accountId(),
        movement.assetId(),
        movement.type(),
        Decimals.format(movement.quantity()),
        movement.date(),
        movement.cost().movePointRight(2).longValueExact());
  }
}
