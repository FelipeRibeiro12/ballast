package com.felipe.ballast.fixedincome;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Os campos de valor são nulos fora da listagem com data e quando a data é antes da aplicação.
@Schema(name = "TituloRendaFixa")
public record TitleResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FixedIncomeType tipo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nome,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long valorAplicadoCentavos,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        LocalDate dataAplicacao,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            type = "string",
            format = "date")
        LocalDate vencimento,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Indexer indexador,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Em texto") String taxa,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) DatedValue resgate,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "O último valor que o banco mostrou")
        DatedValue ultimaMarcacao,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Long valorBrutoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Estimativa; zero em LCI e LCA")
        Long iofCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Estimativa; zero em LCI e LCA")
        Long irCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Bruto menos IOF e IR estimados")
        Long valorLiquidoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Bruto menos aplicado")
        Long rendimentoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            description =
                "True a partir do vencimento: o valor fica congelado nele. Só a listagem traz"
                    + " true")
        boolean vencido,
    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description =
                "Texto curto quando o CDI ou o IPCA guardado não cobre o período do título; o"
                    + " valor sai com o que há")
        String avisoIndice,
    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            allowableValues = "fora do limite",
            description =
                "Preenchido quando o valor não cabe no inteiro seguro do JSON: os campos de valor"
                    + " vêm nulos e o título fica fora do patrimônio")
        String motivoSemValor) {

  @Schema(name = "ValorNaData")
  public record DatedValue(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
          LocalDate data,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long valorCentavos) {}

  static TitleResponse from(FixedIncomeService.Valued valued) {
    return from(
        valued.title(),
        valued.valuation(),
        valued.matured(),
        valued.indexWarning(),
        valued.reason());
  }

  static TitleResponse from(FixedIncomeQueries.Title title, FixedIncomeService.Valuation value) {
    return from(title, value, false, null, null);
  }

  private static TitleResponse from(
      FixedIncomeQueries.Title title,
      FixedIncomeService.Valuation value,
      boolean matured,
      String indexWarning,
      String reason) {
    DatedValue redemption =
        title.redemptionDate() == null
            ? null
            : new DatedValue(title.redemptionDate(), cents(title.redemptionValue()));
    DatedValue mark =
        title.lastMarkDate() == null
            ? null
            : new DatedValue(title.lastMarkDate(), cents(title.lastMarkValue()));
    return new TitleResponse(
        title.id(),
        title.accountId(),
        title.type(),
        title.name(),
        cents(title.invested()),
        title.applicationDate(),
        title.maturity(),
        title.indexer(),
        title.rate().stripTrailingZeros().toPlainString(),
        redemption,
        mark,
        value == null ? null : cents(value.gross()),
        value == null ? null : cents(value.iof()),
        value == null ? null : cents(value.incomeTax()),
        value == null ? null : cents(value.net()),
        value == null ? null : cents(value.gain()),
        matured,
        indexWarning,
        reason);
  }

  // O serviço já conferiu a faixa; longValueExact falha alto se algum valor escapar.
  private static long cents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
