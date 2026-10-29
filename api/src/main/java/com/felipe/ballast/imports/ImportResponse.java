package com.felipe.ballast.imports;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "Importacao")
public record ImportResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"pendente", "confirmada"})
        String status,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "2000")
        int totalLinhas,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Linhas que o app não conseguiu ler; ficam de fora da confirmação")
        int totalInvalidas,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Linhas cuja ação vigente (decisão, ou a proposta sem decisão) é importar")
        int totalImportar,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalConciliar,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalIgnorar,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant criadoEm,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant confirmadaEm) {

  static ImportResponse from(ImportQueries.ImportRow row) {
    return new ImportResponse(
        row.id(),
        row.accountId(),
        row.status(),
        row.totalLines(),
        row.totalInvalid(),
        row.toImport(),
        row.toReconcile(),
        row.toIgnore(),
        row.createdAt(),
        row.confirmedAt());
  }
}
