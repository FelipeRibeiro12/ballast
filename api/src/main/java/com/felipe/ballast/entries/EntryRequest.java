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

@Schema(name = "LancamentoRequest")
public record EntryRequest(
    @JsonProperty("tipo")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = TYPE_MESSAGE)
        EntryType type,
    @JsonProperty("titulo")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        @NotNull(message = TITLE_MESSAGE)
        @CodePointSize(min = 1, max = 100, message = TITLE_MESSAGE)
        @NoControlCharacters(message = TITLE_MESSAGE)
        String title,
    @JsonProperty("descricao")
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 500)
        @CodePointSize(max = 500, message = DESCRIPTION_MESSAGE)
        @NoControlCharacters(message = DESCRIPTION_MESSAGE)
        String description,
    @JsonProperty("valorCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Centavos de real, sempre positivo; o sinal vem do tipo",
            minimum = "1",
            maximum = "9007199254740991")
        @NotNull(message = AMOUNT_MESSAGE)
        @Min(value = 1, message = AMOUNT_MESSAGE)
        @Max(value = MAX_CENTS, message = AMOUNT_MESSAGE)
        Long amountCents,
    @JsonProperty("categoriaId")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        @NotNull(message = "categoriaId é obrigatório")
        String categoryId,
    @JsonProperty("contaId")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        @NotNull(message = "contaId é obrigatório")
        String accountId,
    @JsonProperty("data")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Grava a mesma data em dataCompetencia e dataCaixa",
            type = "string",
            format = "date",
            example = "2026-03-10")
        @NotNull(message = DATE_MESSAGE)
        @ValidIsoDate(message = DATE_MESSAGE)
        String date) {

  // Number.MAX_SAFE_INTEGER: acima disso o JavaScript perde o centavo (ADR-023).
  static final long MAX_CENTS = 9_007_199_254_740_991L;

  static final String TYPE_MESSAGE = "tipo deve ser receita ou despesa";
  static final String TITLE_MESSAGE =
      "titulo deve ter entre 1 e 100 caracteres e não pode ter caracteres de controle";
  static final String DESCRIPTION_MESSAGE =
      "descricao deve ter no máximo 500 caracteres e não pode ter caracteres de controle";
  static final String AMOUNT_MESSAGE =
      "valorCentavos deve ser um inteiro entre 1 e 9007199254740991";
  static final String DATE_MESSAGE =
      "data deve estar no formato AAAA-MM-DD, com ano entre 1900 e 2199";

  // O CHECK do banco recusa espaço nas pontas do titulo: tirar aqui evita 500. Descrição em
  // branco vira nula, para o banco não guardar texto vazio.
  public EntryRequest {
    title = title == null ? null : title.strip();
    description = description == null || description.isBlank() ? null : description.strip();
  }

  // Os getters abaixo não são propriedades do JSON: o Jackson só lê os componentes do record.
  BigDecimal amount() {
    return BigDecimal.valueOf(amountCents, 2);
  }

  LocalDate parsedDate() {
    return IsoDates.parse(date).orElseThrow();
  }
}
