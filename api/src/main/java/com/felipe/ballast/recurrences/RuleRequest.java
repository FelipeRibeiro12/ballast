package com.felipe.ballast.recurrences;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.entries.IsoDates;
import com.felipe.ballast.entries.ValidIsoDate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Optional;

@Schema(name = "RegraRecorrenciaRequest")
public record RuleRequest(
    @JsonProperty("frequencia")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"diaria", "semanal", "mensal", "anual"})
        @NotNull(message = FREQUENCY_MESSAGE)
        RecurrenceFrequency frequency,
    @JsonProperty("intervalo")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            description = "A cada N períodos, contados a partir do período do início; padrão 1",
            minimum = "1",
            maximum = "99")
        @Min(value = 1, message = INTERVAL_MESSAGE)
        @Max(value = 99, message = INTERVAL_MESSAGE)
        Integer interval,
    @JsonProperty("diaDoMes")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description =
                "Só em mensal, e obrigatório nela. Dia maior que o último do mês cai no último dia",
            minimum = "1",
            maximum = "31")
        @Min(value = 1, message = DAY_OF_MONTH_MESSAGE)
        @Max(value = 31, message = DAY_OF_MONTH_MESSAGE)
        Integer dayOfMonth,
    @JsonProperty("diaDaSemana")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description = "Só em semanal, e obrigatório nela. 0 é domingo e 6 é sábado",
            minimum = "0",
            maximum = "6")
        @Min(value = 0, message = DAY_OF_WEEK_MESSAGE)
        @Max(value = 6, message = DAY_OF_WEEK_MESSAGE)
        Integer dayOfWeek,
    @JsonProperty("inicio")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            type = "string",
            format = "date",
            example = "2026-11-05")
        @NotNull(message = START_MESSAGE)
        @ValidIsoDate(message = START_MESSAGE)
        String start,
    @JsonProperty("fim")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            type = "string",
            format = "date",
            description = "Última data possível, incluída; não vale junto com ocorrenciasMax")
        @ValidIsoDate(message = END_MESSAGE)
        String end,
    @JsonProperty("ocorrenciasMax")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description = "Conta as ocorrências desde o início; não vale junto com fim",
            minimum = "1",
            maximum = "10000")
        @Min(value = 1, message = MAX_OCCURRENCES_MESSAGE)
        @Max(value = 10_000, message = MAX_OCCURRENCES_MESSAGE)
        Integer maxOccurrences) {

  static final String FREQUENCY_MESSAGE = "frequencia deve ser diaria, semanal, mensal ou anual";
  static final String INTERVAL_MESSAGE = "intervalo deve ser um inteiro entre 1 e 99";
  static final String DAY_OF_MONTH_MESSAGE = "diaDoMes deve ser um inteiro entre 1 e 31";
  static final String DAY_OF_WEEK_MESSAGE = "diaDaSemana deve ser um inteiro entre 0 e 6";
  static final String START_MESSAGE =
      "inicio deve estar no formato AAAA-MM-DD, com ano entre 1900 e 2199";
  static final String END_MESSAGE =
      "fim deve estar no formato AAAA-MM-DD, com ano entre 1900 e 2199";
  static final String MAX_OCCURRENCES_MESSAGE =
      "ocorrenciasMax deve ser um inteiro entre 1 e 10000";

  // As combinações abaixo são as do CHECK regra_campos_conforme_frequencia e regra_fim_*; a
  // mensagem diz o que corrigir sem repetir o valor enviado. Roda depois da validação por campo.
  Optional<String> crossFieldProblem(boolean hasDivision) {
    // Com divisão o dia vem das parcelas e o servidor preenche o da regra (ADR-027).
    if (frequency == RecurrenceFrequency.MONTHLY && dayOfMonth == null && !hasDivision) {
      return Optional.of("mensal exige diaDoMes");
    }
    if (frequency == RecurrenceFrequency.WEEKLY && dayOfWeek == null) {
      return Optional.of("semanal exige diaDaSemana");
    }
    if (frequency != RecurrenceFrequency.MONTHLY && dayOfMonth != null) {
      return Optional.of("diaDoMes só vale em mensal");
    }
    if (frequency != RecurrenceFrequency.WEEKLY && dayOfWeek != null) {
      return Optional.of("diaDaSemana só vale em semanal");
    }
    if (end != null && maxOccurrences != null) {
      return Optional.of("informe fim ou ocorrenciasMax, não os dois");
    }
    if (end != null && parsedEnd().isBefore(parsedStart())) {
      return Optional.of("fim deve ser igual ou posterior a inicio");
    }
    return Optional.empty();
  }

  // Só depois de a validação por campo passar: as datas já são ISO válidas.
  // Com divisão, o dia da regra é o da parcela de ordem 1: o CHECK mensal exige um dia e quem
  // gera as datas são as parcelas (ADR-027).
  RecurrenceRule toRule(Division division) {
    // Integer e não int: o ternário com int desembrulharia o dayOfMonth nulo das outras
    // frequências.
    Integer ruleDay =
        division == null ? dayOfMonth : Integer.valueOf(division.parts().getFirst().dayOfMonth());
    return new RecurrenceRule(
        frequency,
        interval == null ? 1 : interval,
        ruleDay,
        dayOfWeek,
        parsedStart(),
        end == null ? null : parsedEnd(),
        maxOccurrences);
  }

  private LocalDate parsedStart() {
    return IsoDates.parse(start).orElseThrow();
  }

  private LocalDate parsedEnd() {
    return IsoDates.parse(end).orElseThrow();
  }
}
