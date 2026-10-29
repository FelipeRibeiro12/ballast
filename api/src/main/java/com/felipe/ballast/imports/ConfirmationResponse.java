package com.felipe.ballast.imports;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "ImportacaoConfirmacao")
public record ConfirmationResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"confirmada"})
        String status,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Lançamentos criados")
        int criadas,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Lançamentos existentes que ganharam o vínculo com o extrato")
        int conciliadas,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int ignoradas,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Linhas cujo FITID já tinha entrado quando a confirmação rodou")
        int duplicadas) {

  static ConfirmationResponse from(ImportService.Confirmation confirmation) {
    return new ConfirmationResponse(
        confirmation.importId(),
        "confirmada",
        confirmation.created(),
        confirmation.reconciled(),
        confirmation.ignored(),
        confirmation.duplicated());
  }
}
