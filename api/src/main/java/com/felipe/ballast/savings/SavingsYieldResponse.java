package com.felipe.ballast.savings;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "RendimentoCofrinho")
record SavingsYieldResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "O que a rodada de hoje fez com a conta",
            allowableValues = {
              "lancado",
              "ja_lancado",
              "sem_cdi",
              "sem_rendimento",
              "sem_percentual"
            })
        String situacao,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Rendimento lançado, em centavos; nulo quando não lançou",
            minimum = "1",
            maximum = "9007199254740991")
        Long valor) {

  // Conta alheia, inexistente ou que não é cofrinho não chega aqui: o controller responde 404.
  static SavingsYieldResponse from(SavingsYieldService.Result result) {
    if (result instanceof SavingsYieldService.Result.Launched launched) {
      // longValueExact: centavo que não cabe em long seria erro nosso, não valor para truncar.
      return new SavingsYieldResponse(
          "lancado", launched.amount().movePointRight(2).longValueExact());
    }
    String situation =
        switch ((SavingsYieldService.Result.Skipped) result) {
          case ALREADY_LAUNCHED -> "ja_lancado";
          case NO_CDI -> "sem_cdi";
          case NO_YIELD -> "sem_rendimento";
          case NO_PERCENT -> "sem_percentual";
          case NOT_FOUND -> throw new IllegalArgumentException("conta não encontrada");
        };
    return new SavingsYieldResponse(situation, null);
  }
}
