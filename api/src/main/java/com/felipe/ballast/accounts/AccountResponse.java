package com.felipe.ballast.accounts;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "Conta")
public record AccountResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        String nome,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AccountType tipo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "BRL") String moeda,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Centavos de real, inteiro com sinal",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long saldoInicialCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Centavos de real, inteiro com sinal",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long saldoCentavos,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean arquivada,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant criadaEm,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Dados do cartão; nulo nas contas que não são de cartão de crédito")
        CardResponse cartao,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Ids das categorias de despesa que a conta de benefício aceita, em ordem estável."
                    + " Vazia quando qualquer categoria é aceita; nula nos outros tipos")
        List<UUID> categoriasPermitidas,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Percentual do CDI do cofrinho, em texto com duas casas (ex. \"110.00\"). Nulo"
                    + " fora do cofrinho e no cofrinho sem percentual")
        String percentualCdi) {

  // Única conversão para centavos, na borda de saída. longValueExact falha alto se o banco
  // devolver mais que 2 casas ou um valor fora de long, em vez de arredondar em silêncio.
  static AccountResponse from(AccountSummary summary) {
    return new AccountResponse(
        summary.id(),
        summary.name(),
        summary.type(),
        summary.currency(),
        toCents(summary.initialBalance()),
        toCents(summary.balance()),
        summary.archived(),
        summary.createdAt(),
        summary.card() == null ? null : CardResponse.from(summary.card(), summary.balance()),
        summary.permittedCategories(),
        summary.cdiPercent() == null ? null : summary.cdiPercent().toPlainString());
  }

  private static long toCents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
