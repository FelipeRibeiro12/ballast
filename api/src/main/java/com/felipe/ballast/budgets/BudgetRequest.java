package com.felipe.ballast.budgets;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.YearMonth;

@Schema(name = "OrcamentoRequest")
record BudgetRequest(
    @JsonProperty("tetoCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "1",
            maximum = "9007199254740991")
        @NotNull(message = CEILING_MESSAGE)
        @Min(value = 1, message = CEILING_MESSAGE)
        @Max(value = MAX_CENTS, message = CEILING_MESSAGE)
        Long ceilingCents,
    @JsonProperty("aPartirDe")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            pattern = Months.SHAPE,
            description = "Mês em que o teto começa a valer, AAAA-MM. Vale até a próxima mudança.")
        @NotNull(message = MONTH_MESSAGE)
        @Pattern(regexp = Months.SHAPE, message = MONTH_MESSAGE)
        String fromMonth) {

  static final long MAX_CENTS = 9_007_199_254_740_991L;
  static final String CEILING_MESSAGE =
      "tetoCentavos deve ser um inteiro entre 1 e 9007199254740991";
  static final String MONTH_MESSAGE = "aPartirDe deve ser AAAA-MM com ano entre 1900 e 2199";

  BigDecimal ceiling() {
    return BigDecimal.valueOf(ceilingCents, 2);
  }

  YearMonth parsedMonth() {
    return Months.parse(fromMonth).orElseThrow();
  }
}
