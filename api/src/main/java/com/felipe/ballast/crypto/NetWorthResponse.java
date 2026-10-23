package com.felipe.ballast.crypto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(name = "Patrimonio")
public record NetWorthResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        LocalDate data,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Saldo até a data das contas ativas que não são cripto",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long contasCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Soma do valor das posições com cotação",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long criptoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Soma do líquido estimado dos títulos de renda fixa na data",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long rendaFixaCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long totalCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Posições sem cotação até a data, fora do total")
        List<Unquoted> semCotacao) {

  @Schema(name = "PosicaoSemCotacao")
  public record Unquoted(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ativoId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Em texto")
          String quantidade) {}

  static NetWorthResponse from(LocalDate date, NetWorthService.NetWorth netWorth) {
    List<Unquoted> unquoted =
        netWorth.unquoted().stream()
            .map(p -> new Unquoted(p.accountId(), p.assetId(), Decimals.format(p.quantity())))
            .toList();
    return new NetWorthResponse(
        date,
        cents(netWorth.accounts()),
        cents(netWorth.crypto()),
        cents(netWorth.fixedIncome()),
        cents(netWorth.total()),
        unquoted);
  }

  // O serviço já conferiu a faixa; longValueExact falha alto se algum valor escapar.
  private static long cents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
