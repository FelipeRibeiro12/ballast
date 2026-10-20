package com.felipe.ballast.entries;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "LancamentosVencidos")
public record OverdueResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<EntryResponse> lancamentos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "True quando havia mais de 200 previstas vencidas e a lista foi cortada")
        boolean maisAlgum) {}
