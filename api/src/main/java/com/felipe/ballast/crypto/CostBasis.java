package com.felipe.ballast.crypto;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

// Custo médio ponderado de uma posição, numa moeda (ADR-042). Recebe os movimentos em ordem; o
// valor fica cheio, e quem mostra arredonda uma vez.
final class CostBasis {

  // averageCost é nulo com a posição zerada.
  record Basis(BigDecimal quantity, BigDecimal cost, BigDecimal realized, BigDecimal averageCost) {}

  private BigDecimal quantity = BigDecimal.ZERO;
  private BigDecimal cost = BigDecimal.ZERO;
  private BigDecimal realized = BigDecimal.ZERO;
  private boolean missingCost;

  // amount é o que pagou na compra ou recebeu na venda; nulo quando falta o dólar da data.
  void add(MovementType type, BigDecimal movedQuantity, BigDecimal amount) {
    if (amount == null) {
      missingCost = true;
      return;
    }
    if (type.adds()) {
      quantity = quantity.add(movedQuantity);
      cost = cost.add(amount);
      return;
    }
    // Vender tudo leva o custo inteiro, sem passar pela divisão: a soma das vendas fecha com ele.
    BigDecimal taken =
        movedQuantity.compareTo(quantity) >= 0
            ? cost
            : cost.multiply(movedQuantity).divide(quantity, MathContext.DECIMAL128);
    quantity = quantity.subtract(movedQuantity);
    cost = cost.subtract(taken);
    realized = realized.add(amount.subtract(taken));
  }

  Optional<Basis> result() {
    if (missingCost) {
      return Optional.empty();
    }
    BigDecimal average =
        quantity.signum() == 0 ? null : cost.divide(quantity, MathContext.DECIMAL128);
    return Optional.of(new Basis(quantity, cost, realized, average));
  }
}
