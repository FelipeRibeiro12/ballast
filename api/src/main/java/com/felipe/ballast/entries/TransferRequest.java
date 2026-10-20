package com.felipe.ballast.entries;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(name = "TransferenciaRequest")
public record TransferRequest(
    @JsonProperty("contaOrigemId")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        @NotNull(message = "contaOrigemId é obrigatório")
        String fromAccountId,
    @JsonProperty("contaDestinoId")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        @NotNull(message = "contaDestinoId é obrigatório")
        String toAccountId,
    @JsonProperty("valorCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Centavos de real, sempre positivo",
            minimum = "1",
            maximum = "9007199254740991")
        @NotNull(message = EntryRequest.AMOUNT_MESSAGE)
        @Min(value = 1, message = EntryRequest.AMOUNT_MESSAGE)
        @Max(value = EntryRequest.MAX_CENTS, message = EntryRequest.AMOUNT_MESSAGE)
        Long amountCents,
    @JsonProperty("data")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Grava a mesma data em dataCompetencia e dataCaixa das duas pernas",
            type = "string",
            format = "date",
            example = "2026-03-10")
        @NotNull(message = EntryRequest.DATE_MESSAGE)
        @ValidIsoDate(message = EntryRequest.DATE_MESSAGE)
        String date,
    @JsonProperty("titulo")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            minLength = 1,
            maxLength = 100,
            description = "Ausente, nulo ou em branco vira \"Transferência\"")
        @CodePointSize(min = 1, max = 100, message = EntryRequest.TITLE_MESSAGE)
        @NoControlCharacters(message = EntryRequest.TITLE_MESSAGE)
        String title,
    @JsonProperty("descricao")
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 500)
        @CodePointSize(max = 500, message = EntryRequest.DESCRIPTION_MESSAGE)
        @NoControlCharacters(message = EntryRequest.DESCRIPTION_MESSAGE)
        String description) {

  // Título e descrição em branco viram nulos, como no lançamento: o banco recusa espaço nas pontas
  // e não deve guardar texto vazio. O serviço troca título nulo pelo texto padrão. O construtor
  // roda antes da validação, então o mínimo de 1 caractere nunca vê um título em branco.
  public TransferRequest {
    title = title == null || title.isBlank() ? null : title.strip();
    description = description == null || description.isBlank() ? null : description.strip();
  }

  BigDecimal amount() {
    return BigDecimal.valueOf(amountCents, 2);
  }

  LocalDate parsedDate() {
    return IsoDates.parse(date).orElseThrow();
  }
}
