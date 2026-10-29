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
        @Pattern(regexp = "^\\d{1,4}(?:\\.\\d{1,2})?$", message = PERCENT_MESSAGE)
        String percent) {

  static final String PERCENT_MESSAGE =
      "percentualCdi deve ser um número entre 0.01 e 1000, com até duas casas";

  static final BigDecimal MAX = new BigDecimal("1000");

  // O regex só confere o formato. A faixa é do valor: 1000.50 tem formato certo e passa de 1000.
  boolean inRange() {
    BigDecimal value = value();
    return value.signum() > 0 && value.compareTo(MAX) <= 0;
  }

  BigDecimal value() {
    return new BigDecimal(percent);
  }
}
