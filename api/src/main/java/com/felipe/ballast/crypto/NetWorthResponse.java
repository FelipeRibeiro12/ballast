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
            nullable = true,
            description =
                "Soma do valor em dólares das posições com cotação; nulo quando alguma tem cotação"
                    + " em reais e falta a cotação do dólar para voltar a dólar",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        Long criptoUsdCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Compras menos vendas, em reais, das posições somadas em criptoCentavos (ADR-042);"
                    + " nulo sem o dólar de algum movimento",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        Long criptoAportadoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "criptoCentavos menos criptoAportadoCentavos: o que veio do preço e do dólar, com"
                    + " o ganho das vendas",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        Long criptoValorizacaoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Soma do líquido estimado dos títulos de renda fixa na data",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long rendaFixaCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Saldo na data dos cofrinhos ativos marcados em investimentos (ADR-076). Já está"
                    + " em contasCentavos e no total; não soma de novo",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long cofrinhosCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Os cofrinhos marcados que somam cofrinhosCentavos")
        List<Cofrinho> cofrinhos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long totalCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Posições fora do total: sem cotação até a data, ou com o valor fora do limite")
        List<Unquoted> semCotacao,
    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            description =
                "Títulos de renda fixa com valor fora do limite, que ficam fora de"
                    + " rendaFixaCentavos")
        List<TitleWithoutValue> rendaFixaSemValor) {

  @Schema(name = "CofrinhoEmInvestimentos")
  public record Cofrinho(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nome,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          long saldoCentavos) {}

  @Schema(name = "PosicaoSemCotacao")
  public record Unquoted(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ativoId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Em texto")
          String quantidade,
      @Schema(
              requiredMode = Schema.RequiredMode.NOT_REQUIRED,
              allowableValues = {"sem cotação", "fora do limite"})
          String motivo) {}

  @Schema(name = "TituloSemValor")
  public record TitleWithoutValue(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID tituloId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "fora do limite")
          String motivo) {}

  static NetWorthResponse from(LocalDate date, NetWorthService.NetWorth netWorth) {
    List<Unquoted> unquoted =
        netWorth.unquoted().stream()
            .map(
                item ->
                    new Unquoted(
                        item.position().accountId(),
                        item.position().assetId(),
                        Decimals.format(item.position().quantity()),
                        item.reason()))
            .toList();
    return new NetWorthResponse(
        date,
        cents(netWorth.accounts()),
        cents(netWorth.crypto()),
        nullableCents(netWorth.cryptoUsd()),
        nullableCents(netWorth.cryptoContribution()),
        nullableCents(netWorth.cryptoAppreciation()),
        cents(netWorth.fixedIncome()),
        cents(
            netWorth.savingsPots().stream()
                .map(CryptoQueries.SavingsPot::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add)),
        netWorth.savingsPots().stream()
            .map(pot -> new Cofrinho(pot.id(), pot.name(), cents(pot.balance())))
            .toList(),
        cents(netWorth.total()),
        unquoted,
        netWorth.fixedIncomeExcluded().stream()
            .map(item -> new TitleWithoutValue(item.titleId(), item.reason()))
            .toList());
  }

  // O serviço já conferiu a faixa; longValueExact falha alto se algum valor escapar.
  private static long cents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }

  private static Long nullableCents(BigDecimal amount) {
    return amount == null ? null : cents(amount);
  }
}
