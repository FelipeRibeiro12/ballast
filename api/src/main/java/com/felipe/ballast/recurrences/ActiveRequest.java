package com.felipe.ballast.recurrences;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(name = "RecorrenciaAtivoRequest")
public record ActiveRequest(
    @JsonProperty("ativo")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "false desativa e apaga as previstas futuras; true reativa e gera de novo")
        @NotNull(message = "ativo é obrigatório")
        Boolean active) {}
