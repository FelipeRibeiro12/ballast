package com.felipe.ballast.crypto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "MovimentoCripto")
public record MovementResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ativoId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MovementType tipo,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Em texto, sem zero à direita",
            example = "0.12345678")
        String quantidade,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        LocalDate data,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Em centavos de dólar; nulo no movimento antigo em reais sem PTAX")
        Long custoUsdCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Em centavos de real pela PTAX da data; nulo sem PTAX")
        Long custoCentavos) {

  // Movimento novo é em dólar; o da F13 é em reais (ADR-038). A outra moeda vem pela PTAX da
  // data do movimento, arredondada uma vez com HALF_EVEN.
  static MovementResponse from(CryptoQueries.Movement movement) {
    boolean inUsd = "USD".equals(movement.currency());
    BigDecimal usd =
        inUsd
            ? movement.cost()
            : movement.dollar() == null
                ? null
                : movement.cost().divide(movement.dollar(), MathContext.DECIMAL128);
    BigDecimal brl =
        inUsd
            ? movement.dollar() == null ? null : movement.cost().multiply(movement.dollar())
            : movement.cost();
    return new MovementResponse(
        movement.id(),
        movement.accountId(),
        movement.assetId(),
        movement.type(),
        Decimals.format(movement.quantity()),
        movement.date(),
        cents(usd),
        cents(brl));
  }

  private static Long cents(BigDecimal amount) {
    return amount == null
        ? null
        : amount.setScale(2, RoundingMode.HALF_EVEN).movePointRight(2).longValueExact();
  }
}
