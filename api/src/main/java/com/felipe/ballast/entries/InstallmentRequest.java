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

@Schema(name = "CompraParceladaRequest")
public record InstallmentRequest(
    @JsonProperty("titulo")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        @NotNull(message = EntryRequest.TITLE_MESSAGE)
        @CodePointSize(min = 1, max = 100, message = EntryRequest.TITLE_MESSAGE)
        @NoControlCharacters(message = EntryRequest.TITLE_MESSAGE)
        String title,
    @JsonProperty("descricao")
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 500)
        @CodePointSize(max = 500, message = EntryRequest.DESCRIPTION_MESSAGE)
        @NoControlCharacters(message = EntryRequest.DESCRIPTION_MESSAGE)
        String description,
    @JsonProperty("valorTotalCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Centavos de real. Cada parcela recebe o total dividido por parcelas, truncado em"
                    + " centavos, e a última leva o resto; o total precisa dar ao menos 1 centavo"
                    + " por parcela",
            minimum = "1",
            maximum = "9007199254740991")
        @NotNull(message = TOTAL_MESSAGE)
        @Min(value = 1, message = TOTAL_MESSAGE)
        @Max(value = EntryRequest.MAX_CENTS, message = TOTAL_MESSAGE)
        Long totalCents,
    @JsonProperty("parcelas")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "2", maximum = "48")
        @NotNull(message = COUNT_MESSAGE)
        @Min(value = 2, message = COUNT_MESSAGE)
        @Max(value = 48, message = COUNT_MESSAGE)
        Integer count,
    @JsonProperty("categoriaId")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        @NotNull(message = "categoriaId é obrigatório")
        String categoryId,
    @JsonProperty("contaId")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            format = "uuid",
            description = "Só conta de cartão de crédito do usuário e ativa")
        @NotNull(message = "contaId é obrigatório")
        String accountId,
    @JsonProperty("data")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Data da compra: vira dataCompetencia de todas as parcelas. A dataCaixa da primeira"
                    + " é o vencimento da fatura da compra e a das outras avança um mês por parcela",
            type = "string",
            format = "date",
            example = "2026-03-10")
        @NotNull(message = EntryRequest.DATE_MESSAGE)
        @ValidIsoDate(message = EntryRequest.DATE_MESSAGE)
        String date) {

  static final String TOTAL_MESSAGE =
      "valorTotalCentavos deve ser um inteiro entre 1 e 9007199254740991";
  static final String COUNT_MESSAGE = "parcelas deve ser um inteiro entre 2 e 48";

  // Mesmas normalizações do lançamento: o CHECK do banco recusa espaço nas pontas do título, e
  // descrição em branco vira nula.
  public InstallmentRequest {
    title = title == null ? null : title.strip();
    description = description == null || description.isBlank() ? null : description.strip();
  }

  BigDecimal total() {
    return BigDecimal.valueOf(totalCents, 2);
  }

  LocalDate parsedDate() {
    return IsoDates.parse(date).orElseThrow();
  }
}
