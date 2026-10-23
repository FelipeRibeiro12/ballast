package com.felipe.ballast.crypto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "PosicaoCripto")
public record PositionResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ativoId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Tokens: compras menos vendas até a data, em texto, sem zero à direita")
        String quantidade,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Compras menos vendas em centavos de dólar; nulo sem a PTAX de algum"
                    + " movimento em reais")
        Long custoUsdCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Compras menos vendas em centavos de real, pela PTAX da data de cada"
                    + " movimento; nulo sem a PTAX de algum movimento em dólar")
        Long custoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Última cotação até o fim do dia em America/Sao_Paulo; nula sem cotação")
        QuoteValue cotacao,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Long valorUsdCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Valor em reais pela PTAX da data; nulo sem cotação ou sem PTAX")
        Long valorCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Custo médio ponderado em dólares por unidade, em texto, com 8 casas no máximo"
                    + " (ADR-042); nulo sem a PTAX de algum movimento em reais")
        String custoMedioUsd,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Custo médio das unidades que restam, em centavos de dólar; nulo sem a PTAX de"
                    + " algum movimento em reais")
        Long custoDaPosicaoUsdCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Custo médio das unidades que restam, em centavos de real pela PTAX de cada"
                    + " movimento; nulo sem a PTAX de algum movimento em dólar")
        Long custoDaPosicaoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "valorUsdCentavos menos custoDaPosicaoUsdCentavos: o que o preço fez com o que"
                    + " restou; nulo quando falta um dos dois")
        Long ganhoUsdCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "valorCentavos menos custoDaPosicaoCentavos, com o efeito do dólar; nulo quando"
                    + " falta um dos dois")
        Long ganhoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Recebido nas vendas menos o custo médio do que foi vendido, em centavos de"
                    + " dólar; nulo sem a PTAX de algum movimento em reais")
        Long realizadoUsdCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Recebido nas vendas menos o custo médio do que foi vendido, em centavos de real;"
                    + " nulo sem a PTAX de algum movimento em dólar")
        Long realizadoCentavos) {

  @Schema(name = "CotacaoDaPosicao")
  public record QuoteValue(
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description =
                  "Dólares por unidade, em texto; nulo na cotação antiga em reais sem PTAX")
          String precoUsd,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant momento,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              allowableValues = {"manual", "coingecko"},
              description = "manual quando digitada, coingecko quando veio do job (ADR-040)")
          String fonte) {}

  // dollar é a PTAX da data da posição, para mostrar em dólar a cotação antiga gravada em reais.
  static PositionResponse from(NetWorthService.ValuedPosition valued, BigDecimal dollar) {
    CryptoQueries.Position position = valued.position();
    QuoteValue quote = null;
    if (position.price() != null) {
      BigDecimal usd =
          "USD".equals(position.priceCurrency())
              ? position.price()
              // 8 casas, o formato que a rota de cotação aceita (QA do PR #23).
              : dollar == null ? null : position.price().divide(dollar, 8, RoundingMode.HALF_EVEN);
      quote =
          new QuoteValue(
              usd == null ? null : Decimals.format(usd),
              position.quotedAt(),
              position.quoteSource());
    }
    return new PositionResponse(
        position.accountId(),
        position.assetId(),
        Decimals.format(position.quantity()),
        cents(valued.costUsd()),
        cents(valued.costBrl()),
        quote,
        cents(valued.valueUsd()),
        cents(valued.valueBrl()),
        valued.averageCostUsd() == null ? null : Decimals.format(valued.averageCostUsd()),
        cents(valued.basisUsd()),
        cents(valued.basisBrl()),
        cents(valued.gainUsd()),
        cents(valued.gainBrl()),
        cents(valued.realizedUsd()),
        cents(valued.realizedBrl()));
  }

  // O serviço já arredondou e conferiu a faixa; longValueExact falha alto se algum valor escapar.
  static Long cents(BigDecimal amount) {
    return amount == null ? null : amount.movePointRight(2).longValueExact();
  }
}
