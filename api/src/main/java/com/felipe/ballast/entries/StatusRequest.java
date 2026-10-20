package com.felipe.ballast.entries;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// O valor é lido como texto e conferido no controller: um enum faria o Jackson responder com a
// mensagem genérica de corpo ilegível, e o cliente precisa saber quais valores valem.
@Schema(name = "LancamentoStatusRequest")
public record StatusRequest(
    @JsonProperty("status")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"realizado", "cancelado"},
            description = "Só uma ocorrência prevista muda de status")
        @NotNull(message = "status é obrigatório")
        String status) {}
