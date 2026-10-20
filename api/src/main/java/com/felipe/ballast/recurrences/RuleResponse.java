package com.felipe.ballast.recurrences;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(name = "RegraRecorrencia")
public record RuleResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RecurrenceFrequency frequencia,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "99")
        int intervalo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "Só mensal")
        Integer diaDoMes,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Só semanal; 0 é domingo e 6 é sábado")
        Integer diaDaSemana,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate inicio,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) LocalDate fim,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer ocorrenciasMax) {

  static RuleResponse from(RecurrenceRule rule) {
    return new RuleResponse(
        rule.frequency(),
        rule.interval(),
        rule.dayOfMonth(),
        rule.dayOfWeek(),
        rule.start(),
        rule.end(),
        rule.maxOccurrences());
  }
}
