package com.felipe.ballast.goals;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.entries.IsoDates;
import com.felipe.ballast.entries.ValidIsoDate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(name = "AporteMetaRequest")
record DepositRequest(
    @JsonProperty("valorCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "-9007199254740991",
            maximum = "9007199254740991",
            description = "Positivo guarda, negativo retira. Zero não vale")
        @NotNull(message = VALUE_MESSAGE)
        @Min(value = -GoalRequest.MAX_CENTS, message = VALUE_MESSAGE)
        @Max(value = GoalRequest.MAX_CENTS, message = VALUE_MESSAGE)
        Long cents,
    @JsonProperty("data")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        @NotNull(message = DATE_MESSAGE)
        @ValidIsoDate(message = DATE_MESSAGE)
        String date) {

  static final String VALUE_MESSAGE =
      "valorCentavos deve ser um inteiro diferente de zero, de -9007199254740991 a"
          + " 9007199254740991";
  static final String DATE_MESSAGE = "data deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199";

  BigDecimal value() {
    return BigDecimal.valueOf(cents, 2);
  }

  LocalDate parsedDate() {
    return IsoDates.parse(date).orElseThrow();
  }
}
