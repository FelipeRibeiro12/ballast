package com.felipe.ballast.thirdparties;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "TerceiroLancamento")
public record ThirdPartyEntryResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String titulo,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "despesa é compra do terceiro; receita é reembolso",
            allowableValues = {"despesa", "receita"})
        String tipo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long valorCentavos,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        String dataCaixa,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer parcela,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer totalParcelas) {

  static ThirdPartyEntryResponse from(ThirdPartyQueries.TaggedEntry entry) {
    return new ThirdPartyEntryResponse(
        entry.id(),
        entry.title(),
        entry.type(),
        ThirdPartyResponse.toCents(entry.amount()),
        entry.cashDate().toString(),
        entry.status(),
        entry.installment(),
        entry.installmentTotal());
  }
}
