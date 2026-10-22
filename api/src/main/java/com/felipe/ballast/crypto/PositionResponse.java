package com.felipe.ballast.crypto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "PosicaoCripto")
public record PositionResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ativoId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Compras menos vendas até a data, em texto, sem zero à direita")
        String quantidade,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Custo das compras menos o das vendas, em centavos; negativo quando a"
                    + " venda rendeu mais que o custo",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long custoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Última cotação até o fim do dia em America/Sao_Paulo; nula sem cotação")
        QuoteValue cotacao,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Quantidade vezes preço, em centavos com HALF_EVEN; nulo sem cotação",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        Long valorCentavos) {

  @Schema(name = "CotacaoDaPosicao")
  public record QuoteValue(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Reais por unidade")
          String preco,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant momento) {}

  static PositionResponse from(NetWorthService.ValuedPosition valued) {
    CryptoQueries.Position position = valued.position();
    QuoteValue quote =
        position.price() == null
            ? null
            : new QuoteValue(Decimals.format(position.price()), position.quotedAt());
    return new PositionResponse(
        position.accountId(),
        position.assetId(),
        Decimals.format(position.quantity()),
        cents(position.cost()),
        quote,
        valued.value() == null ? null : cents(valued.value()));
  }

  // A faixa já foi conferida no serviço; longValueExact falha alto se algum valor escapar.
  static long cents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
