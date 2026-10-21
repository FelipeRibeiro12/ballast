package com.felipe.ballast.entries;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "Lancamento")
public record EntryResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EntryType tipo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        String titulo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 500)
        String descricao,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Centavos de real, sempre positivo; o sinal vem do tipo",
            minimum = "1",
            maximum = "9007199254740991")
        long valorCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Nulo só nas pernas de transferência")
        UUID categoriaId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Quando o fato aconteceu; gráfico por categoria e orçamento usam esta")
        LocalDate dataCompetencia,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Quando o dinheiro se move; saldo e fluxo de caixa usam esta")
        LocalDate dataCaixa,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"previsto", "realizado", "cancelado"})
        String status,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant criadoEm,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Id da transferência a que a perna pertence; nulo nos outros tipos")
        UUID transferenciaId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Lado da perna: saida subtrai do saldo, entrada soma; nulo nos outros tipos")
        EntryDirection direcao,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Id da recorrência que gerou a ocorrência; nulo nos avulsos")
        UUID templateId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Ordem da parcela na divisão de salário (1 é a primeira); nulo fora de divisão",
            minimum = "1",
            maximum = "10")
        Integer parcelaOrdem,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Id da compra parcelada (o da primeira parcela) a que o lançamento pertence; nulo"
                    + " nos outros")
        UUID compraParceladaId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Número da parcela na compra parcelada; nulo nos outros",
            minimum = "1",
            maximum = "48")
        Integer parcelaNum,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Quantas parcelas a compra teve ao nascer; nulo nos outros",
            minimum = "2",
            maximum = "48")
        Integer parcelaTotal) {

  // Única conversão para centavos, na borda de saída. longValueExact falha alto se o banco
  // devolver mais que 2 casas ou um valor fora de long, em vez de arredondar em silêncio.
  static EntryResponse from(EntrySummary summary) {
    return new EntryResponse(
        summary.id(),
        summary.type(),
        summary.title(),
        summary.description(),
        toCents(summary.amount()),
        summary.categoryId(),
        summary.accountId(),
        summary.competenceDate(),
        summary.cashDate(),
        summary.status(),
        summary.createdAt(),
        summary.transferId(),
        summary.direction(),
        summary.templateId(),
        summary.parcelOrder(),
        summary.purchaseId(),
        summary.installment(),
        summary.installmentTotal());
  }

  private static long toCents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
