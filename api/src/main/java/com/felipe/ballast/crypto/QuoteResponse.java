package com.felipe.ballast.crypto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "Cotacao")
public record QuoteResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ativoId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant momento,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Dólares por unidade, em texto, sem zero à direita",
            example = "70000.5")
        String precoUsd,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "manual")
        String fonte) {}
