package com.felipe.ballast.crypto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(name = "PosicoesCripto")
public record PositionListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        LocalDate data,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Cotação USDC/BRL usada na conversão: a última até o fim do dia em"
                    + " America/Sao_Paulo; sem ela, a PTAX; nula se não há nenhuma das duas")
        Dollar dolar,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PositionResponse> posicoes,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Toda conta cripto ativa, com o valor das posições confiáveis; conta sem posição"
                    + " vale zero")
        List<Account> contas) {

  @Schema(name = "Dolar")
  public record Dollar(
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Reais por dólar, em texto")
          String valor,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              type = "string",
              format = "date",
              description = "Dia da leitura em America/Sao_Paulo, ou o dia da PTAX")
          LocalDate data,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description = "Instante da leitura do USDC/BRL; nulo quando a fonte é a PTAX")
          Instant momento,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              allowableValues = {"USDC", "PTAX"},
              description = "USDC é a cotação USDC/BRL (ADR-077); PTAX só sem USDC até a data")
          String fonte) {}

  @Schema(name = "ContaCriptoValor")
  public record Account(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description =
                  "Soma dos valores cheios das posições em reais, arredondada uma vez; nulo se"
                      + " alguma posição está sem cotação ou sem dólar",
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          Long valorCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description =
                  "Soma dos valores cheios das posições em dólares, arredondada uma vez; nulo se"
                      + " alguma posição está sem cotação",
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          Long valorUsdCentavos) {}
}
