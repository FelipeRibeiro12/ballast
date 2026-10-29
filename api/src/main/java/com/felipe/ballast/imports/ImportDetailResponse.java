package com.felipe.ballast.imports;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "ImportacaoDetalhe")
public record ImportDetailResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"pendente", "confirmada"})
        String status,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "2000")
        int totalLinhas,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalInvalidas,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalImportar,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalConciliar,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalIgnorar,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant criadoEm,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant confirmadaEm,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ImportLineResponse> linhas) {

  static ImportDetailResponse from(ImportQueries.ImportRow row, List<ImportQueries.LineRow> lines) {
    return new ImportDetailResponse(
        row.id(),
        row.accountId(),
        row.status(),
        row.totalLines(),
        row.totalInvalid(),
        row.toImport(),
        row.toReconcile(),
        row.toIgnore(),
        row.createdAt(),
        row.confirmedAt(),
        lines.stream().map(ImportLineResponse::from).toList());
  }
}
