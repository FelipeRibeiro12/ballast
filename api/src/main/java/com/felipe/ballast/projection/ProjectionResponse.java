package com.felipe.ballast.projection;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(name = "SaldoProjetado")
public record ProjectionResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Hoje em America/Sao_Paulo")
        LocalDate inicio,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Último dia da série")
        LocalDate fim,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "A conta pedida, ou null quando a série soma as contas ativas")
        UUID contaId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Saldo de GET /contas somado nas contas do filtro, todo realizado",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long saldoAtualCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Soma com sinal das previstas com dataCaixa antes de hoje",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long vencidasCentavos,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MenorSaldo menorSaldo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Dia> dias) {

  @Schema(name = "MenorSaldo", description = "Menor saldo da série e o primeiro dia em que ocorre")
  public record MenorSaldo(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate data,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          long saldoCentavos) {}

  @Schema(name = "DiaProjetado")
  public record Dia(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate data,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Entradas com dataCaixa neste dia",
              minimum = "0",
              maximum = "9007199254740991")
          long entradasCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Saídas com dataCaixa neste dia, em número positivo",
              minimum = "0",
              maximum = "9007199254740991")
          long saidasCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Saldo ao fim do dia: saldo inicial mais tudo até dataCaixa deste dia",
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          long saldoCentavos) {}

  // Única conversão para centavos, na borda de saída. longValueExact falha alto se sobrar
  // fração, em vez de arredondar em silêncio.
  static ProjectionResponse from(Projection projection) {
    return new ProjectionResponse(
        projection.start(),
        projection.end(),
        projection.accountId(),
        toCents(projection.currentBalance()),
        toCents(projection.overdue()),
        new MenorSaldo(projection.lowest().date(), toCents(projection.lowest().balance())),
        projection.days().stream()
            .map(
                day ->
                    new Dia(
                        day.date(),
                        toCents(day.inflow()),
                        toCents(day.outflow()),
                        toCents(day.balance())))
            .toList());
  }

  private static long toCents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
