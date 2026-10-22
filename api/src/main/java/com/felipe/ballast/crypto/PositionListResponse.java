package com.felipe.ballast.crypto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(name = "PosicoesCripto")
public record PositionListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        LocalDate data,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PositionResponse> posicoes) {}
