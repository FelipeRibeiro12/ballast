package com.felipe.ballast.identity;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

final class MfaResponses {

  private MfaResponses() {}

  @Schema(name = "MfaStatus")
  record Status(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ativo,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Códigos de recuperação ainda não usados; 0 com o MFA desligado")
          int codigosRestantes) {}

  @Schema(name = "MfaConfiguracao")
  record Setup(
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Segredo em base32, sem \"=\", para digitar no autenticador")
          String segredo,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "otpauth://totp/... para o QR code")
          String uri) {}

  @Schema(name = "MfaAtivacao")
  record Activation(
      @ArraySchema(
              arraySchema =
                  @Schema(
                      requiredMode = Schema.RequiredMode.REQUIRED,
                      description = "Dez códigos de uso único, só nesta resposta"),
              schema = @Schema(pattern = "^[a-z2-9]{5}-[a-z2-9]{5}$"))
          List<String> codigosDeRecuperacao) {}

  @Schema(name = "LoginMfaPendente")
  record Pending(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean mfaPendente) {}
}
