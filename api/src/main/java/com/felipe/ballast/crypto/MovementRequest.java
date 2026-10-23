package com.felipe.ballast.crypto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.entries.IsoDates;
import com.felipe.ballast.entries.ValidIsoDate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(name = "MovimentoCriptoRequest")
public record MovementRequest(
    @JsonProperty("contaId")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        @NotNull(message = "contaId é obrigatório")
        String accountId,
    @JsonProperty("ativoId")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        @NotNull(message = "ativoId é obrigatório")
        String assetId,
    @JsonProperty("tipo")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "tipo deve ser compra ou venda")
        MovementType type,
    @JsonProperty("quantidade")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Em texto: até 20 dígitos inteiros e 18 casas, e no máximo as casas do ativo",
            example = "0.12345678",
            pattern = Decimals.QUANTITY_PATTERN)
        @NotNull(message = QUANTITY_MESSAGE)
        @Pattern(regexp = Decimals.QUANTITY_PATTERN, message = QUANTITY_MESSAGE)
        String quantity,
    @JsonProperty("data")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            type = "string",
            format = "date",
            example = "2026-03-10")
        @NotNull(message = DATE_MESSAGE)
        @ValidIsoDate(message = DATE_MESSAGE)
        String date,
    @JsonProperty("custoUsdCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "O que pagou na compra ou recebeu na venda, em centavos de dólar",
            minimum = "1",
            maximum = "9007199254740991")
        @NotNull(message = COST_MESSAGE)
        @Min(value = 1, message = COST_MESSAGE)
        @Max(value = MAX_CENTS, message = COST_MESSAGE)
        Long costCents) {

  // Number.MAX_SAFE_INTEGER: acima disso o JavaScript perde o centavo (ADR-023).
  static final long MAX_CENTS = 9_007_199_254_740_991L;

  static final String QUANTITY_MESSAGE =
      "quantidade deve ser um decimal maior que zero, em texto, com até 20 dígitos inteiros e 18"
          + " casas";
  static final String DATE_MESSAGE = "data deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199";
  static final String COST_MESSAGE =
      "custoUsdCentavos deve ser um inteiro entre 1 e 9007199254740991";

  BigDecimal parsedQuantity() {
    return new BigDecimal(quantity);
  }

  LocalDate parsedDate() {
    return IsoDates.parse(date).orElseThrow();
  }

  // Centavos de dólar entram como numeric(18,2) exato, sem passar por double.
  BigDecimal costUsd() {
    return BigDecimal.valueOf(costCents, 2);
  }
}
