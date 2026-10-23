package com.felipe.ballast.crypto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(name = "PosicoesCripto")
public record PositionListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        LocalDate data,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "PTAX usada na conversão: a última até a data; nula se ainda não há")
        Dollar dolar,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PositionResponse> posicoes) {

  @Schema(name = "Dolar")
  public record Dollar(
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Reais por dólar, em texto")
          String valor,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
          LocalDate data) {}
}
