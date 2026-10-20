package com.felipe.ballast.entries;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "Transferencia")
public record TransferResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaOrigemId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaDestinoId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Centavos de real, sempre positivo",
            minimum = "1",
            maximum = "9007199254740991")
        long valorCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            type = "string",
            format = "date",
            description = "Data das duas pernas, em dataCompetencia e em dataCaixa")
        LocalDate data,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        String titulo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 500)
        String descricao,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Perna que subtrai da conta de origem")
        UUID saidaId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Perna que soma na conta de destino")
        UUID entradaId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant criadaEm) {

  // Única conversão para centavos, na borda de saída; longValueExact falha alto em vez de
  // arredondar em silêncio.
  static TransferResponse from(TransferSummary summary) {
    return new TransferResponse(
        summary.id(),
        summary.fromAccountId(),
        summary.toAccountId(),
        toCents(summary.amount()),
        summary.date(),
        summary.title(),
        summary.description(),
        summary.outEntryId(),
        summary.inEntryId(),
        summary.createdAt());
  }

  private static long toCents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
