package com.felipe.ballast.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Corpos das rotas de MFA. O toString esconde senha e código, que nunca vão para o log.
final class MfaRequests {

  static final String PASSWORD_MESSAGE = "senha incorreta";
  static final String CODE_MESSAGE = "código inválido";

  private MfaRequests() {}

  @Schema(name = "MfaSenhaRequest")
  record Password(
      @JsonProperty("senha")
          @NotNull(message = PASSWORD_MESSAGE)
          @Size(min = 1, max = 128, message = PASSWORD_MESSAGE)
          String password) {

    @Override
    public String toString() {
      return "Password[***]";
    }
  }

  // 32 cobre o código de recuperação com hífen e espaços; o de 6 dígitos sobra.
  @Schema(name = "MfaCodigoRequest")
  record Code(
      @JsonProperty("codigo")
          @NotNull(message = CODE_MESSAGE)
          @Size(min = 1, max = 32, message = CODE_MESSAGE)
          String code) {

    @Override
    public String toString() {
      return "Code[***]";
    }
  }

  @Schema(name = "MfaDesativarRequest")
  record Disable(
      @JsonProperty("senha")
          @NotNull(message = PASSWORD_MESSAGE)
          @Size(min = 1, max = 128, message = PASSWORD_MESSAGE)
          String password,
      @JsonProperty("codigo")
          @NotNull(message = CODE_MESSAGE)
          @Size(min = 1, max = 32, message = CODE_MESSAGE)
          String code) {

    @Override
    public String toString() {
      return "Disable[***]";
    }
  }
}
