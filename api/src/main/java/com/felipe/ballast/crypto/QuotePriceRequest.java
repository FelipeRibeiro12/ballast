package com.felipe.ballast.crypto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

// O momento é a chave da cotação e vem no caminho; no corpo só o preço corrigido.
@Schema(name = "CotacaoPrecoRequest")
public record QuotePriceRequest(
    @JsonProperty("precoUsd")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Dólares por unidade, em texto: até 12 dígitos inteiros e 8 casas",
            example = "350000.5",
            pattern = Decimals.PRICE_PATTERN)
        @NotNull(message = QuoteRequest.PRICE_MESSAGE)
        @Pattern(regexp = Decimals.PRICE_PATTERN, message = QuoteRequest.PRICE_MESSAGE)
        String price) {

  BigDecimal parsedPrice() {
    return new BigDecimal(price);
  }
}
