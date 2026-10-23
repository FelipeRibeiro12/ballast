package com.felipe.ballast.goals;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

@Schema(name = "Meta")
record GoalResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String titulo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = MAX)
        long valorAlvoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            type = "string",
            format = "date")
        LocalDate dataAlvo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maximum = MAX)
        Long aporteMensalCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Soma dos aportes menos as retiradas",
            minimum = "0",
            maximum = MAX)
        long acumuladoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Alvo menos o acumulado, nunca abaixo de zero",
            minimum = "0",
            maximum = MAX)
        long faltaCentavos,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean atingida,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "A data alvo passou sem atingir")
        boolean prazoVencido,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Quanto aportar por mês, contando o atual, para chegar na data alvo; arredondado"
                    + " para cima. Nulo sem data, com o prazo vencido ou já atingida",
            maximum = MAX)
        Long necessarioPorMesCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            pattern = MONTH,
            description = "Mês em que chega aportando o aporte mensal, contando o atual")
        String chegadaPeloPlano,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Média mensal dos aportes dos três meses fechados antes do atual",
            minimum = "-" + MAX,
            maximum = MAX)
        long ritmoMensalCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            pattern = MONTH,
            description = "Mês em que chega no ritmo atual; nulo sem ritmo positivo")
        String chegadaPeloRitmo) {

  private static final String MAX = "9007199254740991";
  private static final String MONTH = "[0-9]{4}-[0-9]{2}";

  static GoalResponse from(GoalService.ProjectedGoal projected) {
    GoalQueries.Goal goal = projected.goal();
    GoalProjection projection = projected.projection();
    return new GoalResponse(
        goal.id(),
        goal.title(),
        cents(goal.target()),
        goal.targetDate(),
        goal.monthlyPlan() == null ? null : cents(goal.monthlyPlan()),
        cents(goal.saved()),
        cents(projection.remaining()),
        projection.reached(),
        projection.overdue(),
        projection.requiredMonthly() == null ? null : cents(projection.requiredMonthly()),
        month(projection.arrivalByPlan()),
        // HALF_EVEN porque o ritmo é só mostrado: não há direção certa para o meio centavo.
        cents(projection.monthlyPace().setScale(2, RoundingMode.HALF_EVEN)),
        month(projection.arrivalByPace()));
  }

  // Valores com 2 casas e dentro da faixa, garantida pelo CHECK e pelo serviço.
  private static long cents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }

  private static String month(YearMonth month) {
    return month == null ? null : month.toString();
  }
}
