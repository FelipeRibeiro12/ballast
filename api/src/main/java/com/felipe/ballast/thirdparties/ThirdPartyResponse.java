package com.felipe.ballast.thirdparties;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(name = "Terceiro")
public record ThirdPartyResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        String nome,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean arquivado,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Soma das despesas ligadas, sem as canceladas",
            minimum = "0",
            maximum = "9007199254740991")
        long compradoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Soma dos reembolsos realizados",
            minimum = "0",
            maximum = "9007199254740991")
        long recebidoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Comprado menos recebido; negativo quando pagou a mais",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long devidoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Compras com dataCaixa até o fim do mês corrente menos todos os reembolsos"
                    + " realizados, nunca negativo. Reembolso maior que o do mês abate os meses"
                    + " seguintes.",
            minimum = "0",
            maximum = "9007199254740991")
        long devidoNoMesCentavos) {

  static ThirdPartyResponse from(ThirdPartyQueries.ThirdParty found) {
    long bought = toCents(found.bought());
    long received = toCents(found.received());
    // Sem negativo: o reembolso que sobra do mês fica de crédito para os próximos.
    long dueThisMonth = Math.max(0, toCents(found.boughtUntilMonthEnd()) - received);
    return new ThirdPartyResponse(
        found.id(),
        found.name(),
        found.archived(),
        bought,
        received,
        bought - received,
        dueThisMonth);
  }

  static long toCents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
