package com.felipe.ballast.entries;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(name = "CompraParcelada")
public record InstallmentResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Id da primeira parcela; as rotas da compra usam este id")
        UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        String titulo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 500)
        String descricao,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Soma das parcelas que ainda existem, em centavos. Muda se uma parcela for editada"
                    + " ou apagada em /lancamentos")
        long valorTotalCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Quantas parcelas a compra teve ao nascer, de 2 a 48",
            minimum = "2",
            maximum = "48")
        int parcelas,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID categoriaId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Data da compra, a dataCompetencia de todas as parcelas")
        LocalDate dataCompra,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant criadaEm,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "As parcelas que ainda existem, na ordem de parcelaNum")
        List<Item> itens) {

  @Schema(name = "CompraParceladaItem")
  public record Item(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Id em /lancamentos")
          UUID lancamentoId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "48")
          int parcelaNum,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") long valorCentavos,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Vencimento da parcela")
          LocalDate dataCaixa) {}

  // Única conversão para centavos, na borda de saída. longValueExact falha alto em vez de
  // arredondar em silêncio.
  static InstallmentResponse from(InstallmentPurchase purchase) {
    return new InstallmentResponse(
        purchase.id(),
        purchase.title(),
        purchase.description(),
        toCents(purchase.total()),
        purchase.installments(),
        purchase.categoryId(),
        purchase.accountId(),
        purchase.purchaseDate(),
        purchase.createdAt(),
        purchase.items().stream()
            .map(
                item ->
                    new Item(
                        item.entryId(), item.number(), toCents(item.amount()), item.cashDate()))
            .toList());
  }

  private static long toCents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
