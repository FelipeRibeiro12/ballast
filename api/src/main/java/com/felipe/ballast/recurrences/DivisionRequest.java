package com.felipe.ballast.recurrences;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Schema(name = "DivisaoRequest")
public record DivisionRequest(
    @JsonProperty("modo")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"percentual", "valor"})
        @NotNull(message = MODE_MESSAGE)
        DivisionMode mode,
    @JsonProperty("parcelas")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "De 2 a 10 parcelas. A ordem do array é a ordem das parcelas")
        @NotNull(message = PARTS_MESSAGE)
        @Size(min = 2, max = 10, message = PARTS_MESSAGE)
        List<@NotNull(message = PARTS_MESSAGE) @Valid DivisionPartRequest> parts) {

  static final String MODE_MESSAGE = "divisao.modo deve ser percentual ou valor";
  static final String PARTS_MESSAGE = "divisao.parcelas deve ter entre 2 e 10 parcelas";
  static final String MIXED_MESSAGE = "cada parcela deve trazer só o campo do modo da divisao";
  static final String DISTINCT_DAYS_MESSAGE = "os dias das parcelas devem ser distintos";
  static final String PERCENT_SUM_MESSAGE = "os percentuais das parcelas devem somar 10000";
  static final String AMOUNT_SUM_MESSAGE = "os valores das parcelas devem somar o valorCentavos";
  static final String ZERO_PART_MESSAGE = "toda parcela deve valer ao menos 1 centavo";

  // Total de centésimos de ponto percentual: 100% são 10000.
  private static final int FULL_PERCENT_CENTS = 10_000;

  // Roda depois da validação por campo. Modo, soma e dias distintos são o que o banco não
  // garante (ADR-027). A mensagem diz o que corrigir sem repetir o valor enviado.
  Optional<String> crossFieldProblem(long totalCents) {
    boolean percent = mode == DivisionMode.PERCENT;
    long sum = 0;
    Set<Integer> days = new HashSet<>();
    for (DivisionPartRequest part : parts) {
      boolean hasPercent = part.percentCents() != null;
      boolean hasAmount = part.amountCents() != null;
      if (percent ? !hasPercent || hasAmount : !hasAmount || hasPercent) {
        return Optional.of(MIXED_MESSAGE);
      }
      sum += percent ? part.percentCents() : part.amountCents();
      days.add(part.dayOfMonth());
    }
    if (days.size() != parts.size()) {
      return Optional.of(DISTINCT_DAYS_MESSAGE);
    }
    if (percent && sum != FULL_PERCENT_CENTS) {
      return Optional.of(PERCENT_SUM_MESSAGE);
    }
    if (!percent && sum != totalCents) {
      return Optional.of(AMOUNT_SUM_MESSAGE);
    }
    // O CHECK do lançamento exige valor maior que zero: percentual pequeno sobre valor pequeno
    // trunca para 0,00 numa parcela que não é a última.
    boolean hasZeroPart =
        SalarySplit.amounts(BigDecimal.valueOf(totalCents, 2), toDivision()).stream()
            .anyMatch(amount -> amount.signum() <= 0);
    return hasZeroPart ? Optional.of(ZERO_PART_MESSAGE) : Optional.empty();
  }

  // Só depois de a validação passar. A ordem é a posição no array, começando em 1.
  Division toDivision() {
    List<DivisionPart> result = new ArrayList<>(parts.size());
    for (int i = 0; i < parts.size(); i++) {
      DivisionPartRequest part = parts.get(i);
      result.add(
          new DivisionPart(
              i + 1,
              part.dayOfMonth(),
              part.percentCents() == null ? null : BigDecimal.valueOf(part.percentCents(), 2),
              part.amountCents() == null ? null : BigDecimal.valueOf(part.amountCents(), 2)));
    }
    return new Division(mode, result);
  }
}
