package com.felipe.ballast.fixedincome;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.entries.IsoDates;
import com.felipe.ballast.entries.ValidIsoDate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

// Marcação (o valor que o banco mostrou) e resgate (o que caiu na conta): uma data e um valor.
@Schema(name = "ValorNaDataRequest")
public record DatedValueRequest(
    @JsonProperty("data")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        @NotNull(message = DATE_MESSAGE)
        @ValidIsoDate(message = DATE_MESSAGE)
        String date,
    @JsonProperty("valorCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "0",
            maximum = "9007199254740991")
        @NotNull(message = VALUE_MESSAGE)
        @Min(value = 0, message = VALUE_MESSAGE)
        @Max(value = TitleRequest.MAX_CENTS, message = VALUE_MESSAGE)
        Long cents) {

  static final String DATE_MESSAGE = "data deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199";
  static final String VALUE_MESSAGE =
      "valorCentavos deve ser um inteiro entre 0 e 9007199254740991";

  LocalDate parsedDate() {
    return IsoDates.parse(date).orElseThrow();
  }

  BigDecimal value() {
    return BigDecimal.valueOf(cents, 2);
  }
}
