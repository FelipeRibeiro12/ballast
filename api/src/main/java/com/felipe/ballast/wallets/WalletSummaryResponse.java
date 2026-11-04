package com.felipe.ballast.wallets;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(name = "ResumoCarteiras")
public record WalletSummaryResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Wallet> carteiras,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Soma dos valores já arredondados de cada carteira")
        Total total) {

  private static final String PERCENT_NOTE =
      "Variação sobre o valor de 24 h atrás (valor menos variação), em texto com 2 casas;"
          + " nulo quando essa base é zero ou negativa";

  @Schema(name = "ResumoCarteira")
  public record Wallet(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) WalletNetwork rede,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 60)
          String rotulo,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description =
                  "Centavos de dólar inteiros: tokens confiáveis com cotação mais DeFi (empréstimo"
                      + " negativo), sem o que está abaixo de US$ 0,50",
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          long valorUsdCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description = "Centavos de real pelo USDC/BRL; nulo sem cotação do dólar",
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          Long valorBrlCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Variação do valor em 24 h, em centavos de dólar",
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          long variacao24hUsdCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description = "Variação em 24 h em centavos de real; nulo sem cotação do dólar",
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          Long variacao24hBrlCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description = PERCENT_NOTE)
          String variacao24hPercentual,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description =
                  "Algum ativo ficou fora da variação por não ter cotação com 24 h de idade, ou"
                      + " posição DeFi sem variação gravada")
          boolean variacaoParcial) {}

  @Schema(name = "ResumoCarteirasTotal")
  public record Total(
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          long valorUsdCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          Long valorBrlCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          long variacao24hUsdCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          Long variacao24hBrlCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description = PERCENT_NOTE)
          String variacao24hPercentual,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean variacaoParcial) {}

  static WalletSummaryResponse from(WalletSummary summary) {
    return new WalletSummaryResponse(
        summary.wallets().stream()
            .map(
                item -> {
                  WalletSummary.Figures f = item.figures();
                  return new Wallet(
                      item.wallet().id(),
                      item.wallet().network(),
                      item.wallet().label(),
                      f.valueUsdCents(),
                      f.valueBrlCents(),
                      f.change24hUsdCents(),
                      f.change24hBrlCents(),
                      f.change24hPercent(),
                      f.partialChange());
                })
            .toList(),
        new Total(
            summary.total().valueUsdCents(),
            summary.total().valueBrlCents(),
            summary.total().change24hUsdCents(),
            summary.total().change24hBrlCents(),
            summary.total().change24hPercent(),
            summary.total().partialChange()));
  }
}
