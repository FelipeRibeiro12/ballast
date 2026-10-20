package com.felipe.ballast.entries;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

// O status é lido como texto e conferido no controller: um enum faria o Jackson responder com a
// mensagem genérica de corpo ilegível, e o cliente precisa saber quais valores valem. Valor e data
// seguem as regras do lançamento; o controller recusa os dois quando o destino é cancelado.
@Schema(name = "LancamentoStatusRequest")
public record StatusRequest(
    @JsonProperty("status")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"realizado", "cancelado"},
            description = "Só uma ocorrência prevista muda de status")
        @NotNull(message = "status é obrigatório")
        String status,
    @JsonProperty("valorCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            description = "Só com status realizado: valor real, no lugar do previsto",
            minimum = "1",
            maximum = "9007199254740991")
        @Min(value = 1, message = EntryRequest.AMOUNT_MESSAGE)
        @Max(value = EntryRequest.MAX_CENTS, message = EntryRequest.AMOUNT_MESSAGE)
        Long amountCents,
    @JsonProperty("data")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            description =
                "Só com status realizado: data real, gravada em dataCompetencia; dataCaixa recebe a mesma data,"
                    + " ou o vencimento da fatura em conta de cartão",
            type = "string",
            format = "date",
            example = "2026-03-10")
        @ValidIsoDate(message = EntryRequest.DATE_MESSAGE)
        String date) {

  boolean hasAdjustment() {
    return amountCents != null || date != null;
  }

  BigDecimal amount() {
    return amountCents == null ? null : BigDecimal.valueOf(amountCents, 2);
  }

  LocalDate parsedDate() {
    return date == null ? null : IsoDates.parse(date).orElseThrow();
  }
}
