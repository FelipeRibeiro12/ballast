package com.felipe.ballast.imports;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "MapeamentoCsv")
public record CsvMappingResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 60) String nome,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CsvMapping.Separator separador,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CsvMapping.DateFormat formatoData,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CsvMapping.Decimal decimal,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CsvMapping.Sign sinal,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean temCabecalho,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "49")
        int colunaData,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "49")
        int colunaValor,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "49")
        int colunaDescricao,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            minimum = "0",
            maximum = "49")
        Integer colunaId) {

  static CsvMappingResponse from(CsvMappingQueries.Row row) {
    CsvMapping mapping = row.mapping();
    return new CsvMappingResponse(
        row.id(),
        row.name(),
        mapping.separator(),
        mapping.dateFormat(),
        mapping.decimal(),
        mapping.sign(),
        mapping.hasHeader(),
        mapping.dateColumn(),
        mapping.amountColumn(),
        mapping.descriptionColumn(),
        mapping.idColumn());
  }
}
