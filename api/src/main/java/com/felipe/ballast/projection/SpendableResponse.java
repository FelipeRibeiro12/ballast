package com.felipe.ballast.projection;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(name = "PossoGastar")
record SpendableResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        LocalDate hoje,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            type = "string",
            format = "date",
            description =
                "Último dia contado: o dia que o usuário escolheu, a véspera do próximo pagamento da"
                    + " fatura âncora, ou o fim do mês sem cartão")
        LocalDate ate,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "De onde veio a data final da janela",
            allowableValues = {"dia", "fatura", "fim-do-mes"})
        String modo,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Dia do mês escolhido pelo usuário; nulo fora do modo dia",
            minimum = "1",
            maximum = "31")
        Integer diaLimite,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Os saldos dos benefícios entram no livre; ligado, a lista fica vazia")
        boolean incluirBeneficios,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "As compras de terceiros contam como gasto; desligado valem zero")
        boolean incluirTerceiros,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Menor saldo projetado das contas que não são de benefício, de hoje até ate, mais o rendimento."
                    + " Negativo quando falta",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long livreCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Rendimento estimado dos cofrinhos até o dia do menor saldo, já dentro de"
                    + " livreCentavos (ADR-061)",
            minimum = "0",
            maximum = "9007199254740991")
        long rendimentoCofrinhoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            type = "string",
            format = "date",
            description = "Dia do menor saldo")
        LocalDate menorSaldoEm,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Dias de hoje até ate, contando os dois",
            minimum = "1",
            maximum = "366")
        int dias,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "livreCentavos dividido por dias, para baixo; nulo quando não sobra nada",
            minimum = "0",
            maximum = "9007199254740991")
        Long porDiaCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Soma com sinal das previstas vencidas, já dentro de livreCentavos (ADR-029)",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long vencidasCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Contas de benefício ativas, fora de livreCentavos, pelo nome")
        List<Benefit> beneficios) {

  @Schema(name = "BeneficioLivre")
  record Benefit(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nome,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Menor saldo projetado da conta até ate",
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          long livreCentavos) {}

  static SpendableResponse from(SpendableService.Spendable found) {
    return new SpendableResponse(
        found.today(),
        found.until(),
        switch (found.mode()) {
          case CUSTOM_DAY -> "dia";
          case INVOICE_CYCLE -> "fatura";
          case END_OF_MONTH -> "fim-do-mes";
        },
        found.limitDay(),
        found.includeBenefits(),
        found.includeThirdParty(),
        cents(found.spendable()),
        cents(found.savingsYield()),
        found.lowestOn(),
        found.days(),
        found.perDay() == null ? null : cents(found.perDay()),
        cents(found.overdue()),
        found.benefits().stream()
            .map(b -> new Benefit(b.accountId(), b.name(), cents(b.spendable())))
            .toList());
  }

  // Tudo tem 2 casas e o serviço conferiu a faixa: longValueExact falha alto se algo escapar.
  private static long cents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
