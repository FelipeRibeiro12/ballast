package com.felipe.ballast.projection;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(name = "PossoGastarConfiguracaoRequest")
record SpendableConfigRequest(
    @JsonProperty("diaLimite")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            minimum = "1",
            maximum = "31",
            description = "Nulo volta ao ciclo da fatura âncora")
        @Min(value = 1, message = MESSAGE)
        @Max(value = 31, message = MESSAGE)
        Integer limitDay) {

  static final String MESSAGE = "diaLimite deve ser um inteiro de 1 a 31, ou nulo";
}
