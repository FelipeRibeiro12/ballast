package com.felipe.ballast.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostBasisTest {

  private record Step(MovementType type, String quantity, String cost) {}

  private static Step buy(String quantity, String cost) {
    return new Step(MovementType.BUY, quantity, cost);
  }

  private static Step sell(String quantity, String received) {
    return new Step(MovementType.SELL, quantity, received);
  }

  private static CostBasis.Basis fold(Step... steps) {
    CostBasis basis = new CostBasis();
    for (Step step : steps) {
      basis.add(
          step.type(),
          new BigDecimal(step.quantity()),
          step.cost() == null ? null : new BigDecimal(step.cost()));
    }
    return basis.result().orElseThrow();
  }

  @Test
  void buysAddQuantityAndCost() {
    CostBasis.Basis basis = fold(buy("2", "100"), buy("2", "300"));

    assertThat(basis.quantity()).isEqualByComparingTo("4");
    assertThat(basis.cost()).isEqualByComparingTo("400");
    assertThat(basis.realized()).isEqualByComparingTo("0");
    assertThat(basis.averageCost()).isEqualByComparingTo("100");
  }

  // Custo médio: a venda leva a fração vendida do custo, e o que recebeu acima disso é ganho.
  @Test
  void aSaleTakesTheAverageCostAndTheRestIsRealized() {
    CostBasis.Basis basis = fold(buy("2", "100"), buy("2", "300"), sell("1", "150"));

    assertThat(basis.quantity()).isEqualByComparingTo("3");
    assertThat(basis.cost()).isEqualByComparingTo("300");
    assertThat(basis.realized()).isEqualByComparingTo("50");
    assertThat(basis.averageCost()).isEqualByComparingTo("100");
  }

  @Test
  void aSaleBelowTheAverageCostIsALoss() {
    CostBasis.Basis basis = fold(buy("1", "100"), sell("0.5", "30"));

    assertThat(basis.cost()).isEqualByComparingTo("50");
    assertThat(basis.realized()).isEqualByComparingTo("-20");
  }

  // Três vendas de um terço: cada uma leva 33,33... e a soma tem de fechar com o custo.
  @Test
  void sellingEverythingInThirdsTakesExactlyTheWholeCost() {
    CostBasis.Basis basis =
        fold(buy("3", "100"), sell("1", "40"), sell("1", "40"), sell("1", "40"));

    assertThat(basis.quantity()).isEqualByComparingTo("0");
    assertThat(basis.cost()).isEqualByComparingTo("0");
    assertThat(basis.realized()).isEqualByComparingTo("20");
    assertThat(basis.averageCost()).isNull();
  }

  // Compras menos vendas é o custo que sobrou menos o ganho realizado: a conta da tela fecha.
  @Test
  void netContributionIsTheRemainingCostMinusTheRealizedGain() {
    List<Step> steps = new ArrayList<>();
    steps.add(buy("0.7", "1234.56"));
    steps.add(sell("0.2", "500.01"));
    steps.add(buy("1.3", "999.99"));
    steps.add(sell("0.9", "1000"));
    CostBasis.Basis basis = fold(steps.toArray(Step[]::new));

    BigDecimal net =
        new BigDecimal("1234.56")
            .subtract(new BigDecimal("500.01"))
            .add(new BigDecimal("999.99"))
            .subtract(new BigDecimal("1000"));
    assertThat(basis.cost().subtract(basis.realized())).isEqualByComparingTo(net);
  }

  @Test
  void aMovementWithoutCostLeavesNoBasis() {
    CostBasis basis = new CostBasis();
    basis.add(MovementType.BUY, new BigDecimal("1"), new BigDecimal("10"));
    basis.add(MovementType.BUY, new BigDecimal("1"), null);

    assertThat(basis.result()).isEmpty();
  }
}
