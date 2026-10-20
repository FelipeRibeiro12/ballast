package com.felipe.ballast.recurrences;

import com.felipe.ballast.entries.EntryType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "Recorrencia")
public record RecurrenceResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"receita", "despesa"})
        EntryType tipo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        String titulo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 500)
        String descricao,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Centavos de real, sempre positivo; o sinal vem do tipo",
            minimum = "1",
            maximum = "9007199254740991")
        long valorCentavos,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID categoriaId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ativo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant criadoEm,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RuleResponse regra,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Menor dataCaixa entre as previstas de hoje em diante; nula se não houver ou se inativa")
        LocalDate proximaOcorrencia) {

  // Única conversão para centavos, na borda de saída. longValueExact falha alto se o banco
  // devolver mais que 2 casas ou um valor fora de long, em vez de arredondar em silêncio.
  static RecurrenceResponse from(RecurrenceSummary summary) {
    return new RecurrenceResponse(
        summary.id(),
        summary.type(),
        summary.title(),
        summary.description(),
        toCents(summary.amount()),
        summary.categoryId(),
        summary.accountId(),
        summary.active(),
        summary.createdAt(),
        RuleResponse.from(summary.rule()),
        summary.nextOccurrence());
  }

  private static long toCents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
