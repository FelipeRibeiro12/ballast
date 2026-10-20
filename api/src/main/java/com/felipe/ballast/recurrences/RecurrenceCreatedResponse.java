package com.felipe.ballast.recurrences;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "RecorrenciaCriada")
public record RecurrenceCreatedResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RecurrenceResponse recorrencia,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Quantas ocorrências previstas a criação gravou (hoje até hoje mais 12 meses)")
        int ocorrenciasMaterializadas) {}
