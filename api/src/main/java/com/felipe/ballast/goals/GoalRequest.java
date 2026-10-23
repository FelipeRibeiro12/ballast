package com.felipe.ballast.goals;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.entries.IsoDates;
import com.felipe.ballast.entries.ValidIsoDate;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Schema(name = "MetaRequest")
record GoalRequest(
    @JsonProperty("titulo")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        @NotNull(message = TITLE_MESSAGE)
        @CodePointSize(min = 1, max = 100, message = TITLE_MESSAGE)
        @NoControlCharacters(message = TITLE_MESSAGE)
        String title,
    @JsonProperty("valorAlvoCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "1",
            maximum = "9007199254740991")
        @NotNull(message = TARGET_MESSAGE)
        @Min(value = 1, message = TARGET_MESSAGE)
        @Max(value = MAX_CENTS, message = TARGET_MESSAGE)
        Long targetCents,
    @JsonProperty("dataAlvo")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            type = "string",
            format = "date")
        @ValidIsoDate(message = DATE_MESSAGE)
        String targetDate,
    @JsonProperty("aporteMensalCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            minimum = "1",
            maximum = "9007199254740991",
            description = "Quanto pretende guardar por mês. É plano: nada se lança sozinho")
        @Min(value = 1, message = PLAN_MESSAGE)
        @Max(value = MAX_CENTS, message = PLAN_MESSAGE)
        Long monthlyPlanCents) {

  // Number.MAX_SAFE_INTEGER: acima disso o JavaScript perde o centavo (ADR-023).
  static final long MAX_CENTS = 9_007_199_254_740_991L;

  static final String TITLE_MESSAGE =
      "titulo deve ter entre 1 e 100 caracteres e não pode ter caracteres de controle";
  static final String TARGET_MESSAGE =
      "valorAlvoCentavos deve ser um inteiro entre 1 e 9007199254740991";
  static final String DATE_MESSAGE =
      "dataAlvo deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199";
  static final String PLAN_MESSAGE =
      "aporteMensalCentavos deve ser um inteiro entre 1 e 9007199254740991";

  // O CHECK do banco recusa espaço nas pontas. Data em branco é sem data.
  GoalRequest {
    title = title == null ? null : title.strip();
    targetDate = targetDate == null || targetDate.isBlank() ? null : targetDate;
  }

  GoalQueries.Fields fields() {
    return new GoalQueries.Fields(
        title,
        BigDecimal.valueOf(targetCents, 2),
        targetDate == null ? null : IsoDates.parse(targetDate).orElseThrow(),
        monthlyPlanCents == null ? null : BigDecimal.valueOf(monthlyPlanCents, 2));
  }
}
