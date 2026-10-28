package com.felipe.ballast.accounts;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

@Schema(name = "CofrinhoRequest")
record SavingsPotRequest(
    @JsonProperty("percentualCdi")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "110",
            description = "Percentual do CDI, de 0,01 a 1000, com até duas casas")
        @NotNull(message = PERCENT_MESSAGE)
        @Pattern(regexp = "^(?:[1-9]\\d{0,2}|1000|0)(?:\\.\\d{1,2})?$", message = PERCENT_MESSAGE)
        String percent) {

  static final String PERCENT_MESSAGE =
      "percentualCdi deve ser um número entre 0.01 e 1000, com até duas casas";

  BigDecimal value() {
    return new BigDecimal(percent);
  }
}
