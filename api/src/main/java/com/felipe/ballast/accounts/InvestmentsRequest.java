package com.felipe.ballast.accounts;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(name = "EmInvestimentosRequest")
record InvestmentsRequest(
    @JsonProperty("emInvestimentos")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Se o saldo do cofrinho conta na tela Investimentos")
        @NotNull(message = "emInvestimentos é obrigatório")
        Boolean inInvestments) {}
