package com.felipe.ballast.accounts;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ResumoExclusaoConta")
public record DeletionSummaryResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Lançamentos da conta que não são perna de transferência")
        long lancamentos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Transferências com outra conta; a perna de lá vira lançamento comum")
        long transferencias,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long recorrencias,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long movimentosCripto) {

  static DeletionSummaryResponse from(AccountDeletionQueries.Summary summary) {
    return new DeletionSummaryResponse(
        summary.entries(), summary.transfers(), summary.recurrences(), summary.cryptoMovements());
  }
}
