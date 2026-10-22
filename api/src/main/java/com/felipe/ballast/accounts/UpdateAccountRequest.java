package com.felipe.ballast.accounts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

// Todo campo é opcional; ausente ou nulo não muda (ADR-036).
@Schema(name = "AtualizarContaRequest")
public record UpdateAccountRequest(
    @JsonProperty("arquivada") @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean archived,
    @JsonProperty("nome")
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, minLength = 1, maxLength = 100)
        @CodePointSize(min = 1, max = 100, message = CreateAccountRequest.NAME_MESSAGE)
        @NoControlCharacters(message = CreateAccountRequest.NAME_MESSAGE)
        String name,
    @JsonProperty("saldoInicialCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        @Min(
            value = -CreateAccountRequest.MAX_CENTS,
            message = CreateAccountRequest.BALANCE_MESSAGE)
        @Max(value = CreateAccountRequest.MAX_CENTS, message = CreateAccountRequest.BALANCE_MESSAGE)
        Long initialBalanceCents,
    @JsonProperty("cartao")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            description = "Só em conta de cartão. Fechamento e vencimento não se editam (ADR-031)")
        @Valid
        UpdateCardRequest card) {

  // O CHECK do banco recusa espaço nas pontas.
  public UpdateAccountRequest {
    name = name == null ? null : name.strip();
  }

  boolean isEmpty() {
    return archived == null && name == null && initialBalanceCents == null && card == null;
  }

  BigDecimal initialBalance() {
    return initialBalanceCents == null ? null : BigDecimal.valueOf(initialBalanceCents, 2);
  }

  @Schema(name = "AtualizarCartaoRequest")
  public record UpdateCardRequest(
      @JsonProperty("limiteCentavos")
          @Schema(
              requiredMode = Schema.RequiredMode.NOT_REQUIRED,
              minimum = "1",
              maximum = "9007199254740991")
          @Min(value = 1, message = CardRequest.LIMIT_MESSAGE)
          @Max(value = CreateAccountRequest.MAX_CENTS, message = CardRequest.LIMIT_MESSAGE)
          Long limitCents,
      @JsonProperty("contaPagamentoId")
          @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, format = "uuid")
          String paymentAccountId) {

    BigDecimal limit() {
      return limitCents == null ? null : BigDecimal.valueOf(limitCents, 2);
    }
  }
}
