package com.felipe.ballast.accounts;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(name = "AtualizarContaRequest")
public record UpdateAccountRequest(
    @JsonProperty("arquivada")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "arquivada é obrigatória e deve ser true ou false")
        Boolean archived) {}
