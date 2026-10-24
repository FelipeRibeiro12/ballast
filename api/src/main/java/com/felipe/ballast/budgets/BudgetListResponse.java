package com.felipe.ballast.budgets;

import com.felipe.ballast.web.Months;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Schema(name = "OrcamentosDoMes")
record BudgetListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = Months.SHAPE) String mes,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Categorias com teto no mês, pelo nome")
        List<Item> orcamentos) {

  @Schema(name = "Orcamento")
  record Item(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID categoriaId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String categoriaNome,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              minimum = "1",
              maximum = "9007199254740991")
          long tetoCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              pattern = Months.SHAPE,
              description = "Mês em que este teto começou a valer")
          String desde,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description =
                  "Despesas realizadas da categoria e das filhas com competência no mês até hoje",
              minimum = "0",
              maximum = "9007199254740991")
          long gastoCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description =
                  "Despesas do mês que ainda não aconteceram: previstas e com data depois de hoje",
              minimum = "0",
              maximum = "9007199254740991")
          long previstoCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "tetoCentavos menos gastoCentavos; negativo quando o gasto passou",
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          long restanteCentavos) {}

  static BudgetListResponse from(YearMonth month, List<BudgetService.Budget> budgets) {
    return new BudgetListResponse(
        month.toString(),
        budgets.stream()
            .map(
                b ->
                    new Item(
                        b.categoryId(),
                        b.categoryName(),
                        cents(b.ceiling()),
                        b.since().toString(),
                        cents(b.spent()),
                        cents(b.planned()),
                        cents(b.left())))
            .toList());
  }

  // Valor de lançamento e teto têm 2 casas, e o serviço conferiu a faixa: longValueExact falha
  // alto se algo escapar.
  private static long cents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
