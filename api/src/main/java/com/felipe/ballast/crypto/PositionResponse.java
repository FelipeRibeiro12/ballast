package com.felipe.ballast.crypto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.MathContext;
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
        Long valorCentavos) {

  @Schema(name = "CotacaoDaPosicao")
  public record QuoteValue(
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description =
                  "Dólares por unidade, em texto; nulo na cotação antiga em reais sem PTAX")
          String precoUsd,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant momento) {}

  // dollar é a PTAX da data da posição, para mostrar em dólar a cotação antiga gravada em reais.
  static PositionResponse from(NetWorthService.ValuedPosition valued, BigDecimal dollar) {
    CryptoQueries.Position position = valued.position();
    QuoteValue quote = null;
    if (position.price() != null) {
      BigDecimal usd =
          "USD".equals(position.priceCurrency())
              ? position.price()
              : dollar == null ? null : position.price().divide(dollar, MathContext.DECIMAL128);
      quote = new QuoteValue(usd == null ? null : Decimals.format(usd), position.quotedAt());
    }
    return new PositionResponse(
        position.accountId(),
        position.assetId(),
        Decimals.format(position.quantity()),
        cents(valued.costUsd()),
        cents(valued.costBrl()),
        quote,
        cents(valued.valueUsd()),
        cents(valued.valueBrl()));
  }

  // O serviço já arredondou e conferiu a faixa; longValueExact falha alto se algum valor escapar.
  static Long cents(BigDecimal amount) {
    return amount == null ? null : amount.movePointRight(2).longValueExact();
  }
}
