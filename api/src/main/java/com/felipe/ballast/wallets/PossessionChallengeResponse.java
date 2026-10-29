package com.felipe.ballast.wallets;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "DesafioPosse")
public record PossessionChallengeResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "9f2c4b7a1e3d5c6b8a0f1e2d3c4b5a69")
        String nonce,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Texto exato que o usuário assina, sem alterar nenhum caractere")
        String mensagem,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Cinco minutos depois da emissão")
        Instant expiraEm) {}
