package com.felipe.ballast.accounts;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Corpo opcional do DELETE /contas/{id}. O toString esconde o código, que nunca vai para o log.
@Schema(name = "ApagarContaRequest")
public record DeleteAccountRequest(
    @JsonProperty("codigo")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Código do autenticador ou de recuperação, de 1 a 32 caracteres")
        @NotNull(message = "código inválido")
        @Size(min = 1, max = 32, message = "código inválido")
        String code) {

  @Override
  public String toString() {
    return "DeleteAccountRequest[***]";
  }
}
