package com.felipe.ballast.imports;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "ImportacaoDuplicada")
public record DuplicateImportResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String erro,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Importação que já recebeu este arquivo")
        UUID importacaoId) {}
