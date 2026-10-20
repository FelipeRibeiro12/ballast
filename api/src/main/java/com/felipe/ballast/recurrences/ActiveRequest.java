package com.felipe.ballast.recurrences;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(name = "RecorrenciaAtivoRequest")
public record ActiveRequest(
    @JsonProperty("ativo")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Só false é aceito: a recorrência não é reativada")
        @NotNull(message = "ativo é obrigatório")
        Boolean active) {}
