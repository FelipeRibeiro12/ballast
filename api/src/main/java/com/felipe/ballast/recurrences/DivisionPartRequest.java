package com.felipe.ballast.recurrences;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(name = "ParcelaDivisaoRequest")
public record DivisionPartRequest(
    @JsonProperty("diaDoMes")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Dia maior que o último do mês cai no último dia",
            minimum = "1",
            maximum = "31")
        @NotNull(message = DAY_MESSAGE)
        @Min(value = 1, message = DAY_MESSAGE)
        @Max(value = 31, message = DAY_MESSAGE)
        Integer dayOfMonth,
    @JsonProperty("percentualCentesimos")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description =
                "Só no modo percentual. Centésimos de ponto percentual: 40% é 4000, 33,33% é 3333",
            minimum = "1",
            maximum = "9999")
        @Min(value = 1, message = PERCENT_MESSAGE)
        @Max(value = 9999, message = PERCENT_MESSAGE)
        Integer percentCents,
    @JsonProperty("valorCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description = "Só no modo valor. Centavos de real",
            minimum = "1",
            maximum = "9007199254740991")
        @Min(value = 1, message = AMOUNT_MESSAGE)
        @Max(value = RecurrenceRequest.MAX_CENTS, message = AMOUNT_MESSAGE)
        Long amountCents) {

  static final String DAY_MESSAGE = "diaDoMes da parcela deve ser um inteiro entre 1 e 31";
  static final String PERCENT_MESSAGE = "percentualCentesimos deve ser um inteiro entre 1 e 9999";
  static final String AMOUNT_MESSAGE =
      "valorCentavos da parcela deve ser um inteiro entre 1 e 9007199254740991";
}
