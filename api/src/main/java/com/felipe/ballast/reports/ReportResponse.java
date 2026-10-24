package com.felipe.ballast.reports;

import com.felipe.ballast.web.Months;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(name = "RelatorioDoMes")
record ReportResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = Months.SHAPE) String mes,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Soma de despesasPorCategoria",
            minimum = "0",
            maximum = "9007199254740991")
        long totalDespesasCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Despesas realizadas com competência no mês até hoje, por categoria de cima, da"
                    + " maior para a menor")
        List<Category> despesasPorCategoria,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Os seis meses que terminam em mes, do mais antigo para o mais novo, com zero"
                    + " nos meses sem lançamento")
        List<Month> receitasEDespesas) {

  @Schema(name = "DespesaDaCategoria")
  record Category(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID categoriaId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String categoriaNome,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Inclui as despesas das categorias filhas",
              minimum = "1",
              maximum = "9007199254740991")
          long gastoCentavos) {}

  @Schema(name = "ReceitaEDespesaDoMes")
  record Month(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "AAAA-MM") String mes,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              minimum = "0",
              maximum = "9007199254740991")
          long receitaCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              minimum = "0",
              maximum = "9007199254740991")
          long despesaCentavos) {}

  static ReportResponse from(ReportService.Report report) {
    return new ReportResponse(
        report.month().toString(),
        cents(report.totalExpense()),
        report.categories().stream()
            .map(c -> new Category(c.categoryId(), c.categoryName(), cents(c.spent())))
            .toList(),
        report.months().stream()
            .map(m -> new Month(m.month().toString(), cents(m.income()), cents(m.expense())))
            .toList());
  }

  // Valor de lançamento tem 2 casas e o serviço conferiu a faixa: longValueExact falha alto se
  // algo escapar.
  private static long cents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
