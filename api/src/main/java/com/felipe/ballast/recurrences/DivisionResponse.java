package com.felipe.ballast.recurrences;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Schema(name = "DivisaoRecorrencia")
public record DivisionResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"percentual", "valor"})
        DivisionMode modo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PartResponse> parcelas) {

  @Schema(name = "ParcelaDivisao")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PartResponse(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "10") int ordem,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "31")
          int diaDoMes,
      @Schema(
              requiredMode = Schema.RequiredMode.NOT_REQUIRED,
              description = "Só no modo percentual. 40% é 4000, 33,33% é 3333",
              minimum = "1",
              maximum = "9999")
          Integer percentualCentesimos,
      @Schema(
              requiredMode = Schema.RequiredMode.NOT_REQUIRED,
              description = "Só no modo valor. Centavos de real",
              minimum = "1",
              maximum = "9007199254740991")
          Long valorCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Centavos que a parcela gera em cada mês, com a regra do centavo",
              minimum = "1",
              maximum = "9007199254740991")
          long valorParcelaCentavos) {}

  // Conversão para inteiro na borda de saída. intValueExact e longValueExact falham alto se o
  // banco devolver mais casas que o esperado, em vez de arredondar em silêncio.
  static DivisionResponse from(BigDecimal total, Division division) {
    List<BigDecimal> amounts = SalarySplit.amounts(total, division);
    List<PartResponse> parts = new ArrayList<>(amounts.size());
    for (int i = 0; i < amounts.size(); i++) {
      DivisionPart part = division.parts().get(i);
      parts.add(
          new PartResponse(
              part.order(),
              part.dayOfMonth(),
              part.percent() == null ? null : part.percent().movePointRight(2).intValueExact(),
              part.fixedAmount() == null
                  ? null
                  : part.fixedAmount().movePointRight(2).longValueExact(),
              amounts.get(i).movePointRight(2).longValueExact()));
    }
    return new DivisionResponse(division.mode(), parts);
  }
}
