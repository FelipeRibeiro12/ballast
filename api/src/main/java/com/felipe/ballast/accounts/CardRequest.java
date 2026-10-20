package com.felipe.ballast.accounts;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Schema(name = "CartaoRequest")
public record CardRequest(
    @JsonProperty("limiteCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Centavos de real. Informativo: não bloqueia lançamento",
            minimum = "1",
            maximum = "9007199254740991")
        @NotNull(message = LIMIT_MESSAGE)
        @Min(value = 1, message = LIMIT_MESSAGE)
        @Max(value = CreateAccountRequest.MAX_CENTS, message = LIMIT_MESSAGE)
        Long limitCents,
    @JsonProperty("diaFechamento")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "31")
        @NotNull(message = CLOSING_MESSAGE)
        @Min(value = 1, message = CLOSING_MESSAGE)
        @Max(value = 31, message = CLOSING_MESSAGE)
        Integer closingDay,
    @JsonProperty("diaVencimento")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "31")
        @NotNull(message = DUE_MESSAGE)
        @Min(value = 1, message = DUE_MESSAGE)
        @Max(value = 31, message = DUE_MESSAGE)
        Integer dueDay,
    @JsonProperty("contaPagamentoId")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            format = "uuid",
            description = "Conta corrente ou poupança ativa do usuário, de onde sai a fatura")
        @NotNull(message = PAYMENT_MESSAGE)
        String paymentAccountId) {

  static final String LIMIT_MESSAGE =
      "limiteCentavos deve ser um inteiro entre 1 e 9007199254740991";
  static final String CLOSING_MESSAGE = "diaFechamento deve ser um inteiro entre 1 e 31";
  static final String DUE_MESSAGE = "diaVencimento deve ser um inteiro entre 1 e 31";
  static final String PAYMENT_MESSAGE = "contaPagamentoId é obrigatório";

  // Centavos inteiros entram como numeric(18,2) exato, sem passar por double.
  BigDecimal limit() {
    return BigDecimal.valueOf(limitCents, 2);
  }
}
