package com.felipe.ballast.crypto;

import com.felipe.ballast.web.Months;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@Schema(name = "PatrimonioMensal")
record NetWorthHistoryResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = Months.SHAPE) String mes,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Até doze meses terminando em mes, do mais antigo para o mais novo. Mês depois do"
                    + " corrente fica de fora")
        List<Month> meses) {

  @Schema(name = "PatrimonioDoMes")
  record Month(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "AAAA-MM") String mes,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              type = "string",
              format = "date",
              description = "Último dia do mês, ou hoje no mês corrente")
          LocalDate data,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          long contasCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          long criptoCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          long rendaFixaCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              minimum = "-9007199254740991",
              maximum = "9007199254740991")
          long totalCentavos,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Posições sem cotação na data, fora do total",
              minimum = "0")
          int posicoesSemCotacao) {}

  static NetWorthHistoryResponse from(YearMonth month, List<NetWorthService.MonthEnd> months) {
    return new NetWorthHistoryResponse(
        month.toString(),
        months.stream()
            .map(
                m ->
                    new Month(
                        m.month().toString(),
                        m.date(),
                        cents(m.netWorth().accounts()),
                        cents(m.netWorth().crypto()),
                        cents(m.netWorth().fixedIncome()),
                        cents(m.netWorth().total()),
                        m.netWorth().unquoted().size()))
            .toList());
  }

  // O serviço já deixou tudo em centavos e conferiu a faixa.
  private static long cents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
