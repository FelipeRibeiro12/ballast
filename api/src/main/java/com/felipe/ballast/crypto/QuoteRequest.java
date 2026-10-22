package com.felipe.ballast.crypto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

@Schema(name = "CotacaoRequest")
public record QuoteRequest(
    @JsonProperty("preco")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Reais por unidade, em texto: até 12 dígitos inteiros e 8 casas",
            example = "350000.5",
            pattern = Decimals.PRICE_PATTERN)
        @NotNull(message = PRICE_MESSAGE)
        @Pattern(regexp = Decimals.PRICE_PATTERN, message = PRICE_MESSAGE)
        String price,
    @JsonProperty("momento")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            type = "string",
            format = "date-time",
            description = "ISO-8601 com fuso. Ausente vale o instante da requisição",
            example = "2026-10-21T15:00:00-03:00")
        String moment) {

  static final String PRICE_MESSAGE =
      "preco deve ser um decimal maior que zero, em texto, com até 12 dígitos inteiros e 8 casas";

  // O formato já foi conferido pela anotação; o texto vira BigDecimal sem passar por double.
  BigDecimal parsedPrice() {
    return new BigDecimal(price);
  }
}
