package com.felipe.ballast.imports;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

@Schema(name = "CsvPrevia")
public record CsvPreviewResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Linhas de dados do arquivo inteiro, sem o cabeçalho",
            minimum = "1",
            maximum = "2000")
        int totalLinhas,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Colunas da primeira linha do arquivo",
            minimum = "1",
            maximum = "50")
        int colunasDetectadas,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "As 10 primeiras linhas")
        List<Row> linhas) {

  static final int PREVIEW_ROWS = 10;

  @Schema(name = "CsvPreviaLinha")
  public record Row(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int ordem,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description =
                  "Células da linha, cortadas em 200 caracteres e com apóstrofo na frente das que"
                      + " começam como fórmula")
          List<String> celulas,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean valida,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description = "Por que a linha é inválida; texto fixo, sem trecho do arquivo")
          String erro,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              type = "string",
              format = "date")
          LocalDate data,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              allowableValues = {"receita", "despesa"})
          String tipo,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description = "Centavos de real, sempre positivo; o sinal vem do tipo",
              minimum = "1",
              maximum = "9007199254740991")
          Long valorCentavos,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
          String titulo) {}

  // Única conversão para centavos, na borda de saída (CLAUDE.md).
  static CsvPreviewResponse from(CsvFile file) {
    List<OfxTransaction> rows = file.transactions();
    return new CsvPreviewResponse(
        rows.size(),
        file.firstRowColumns(),
        IntStream.range(0, Math.min(rows.size(), PREVIEW_ROWS))
            .mapToObj(i -> row(rows.get(i)))
            .toList());
  }

  private static Row row(OfxTransaction tx) {
    boolean valid = tx.invalidReason() == null;
    return new Row(
        tx.order(),
        List.copyOf(tx.raw().values()),
        valid,
        tx.invalidReason(),
        tx.date(),
        valid ? (tx.amount().signum() > 0 ? "receita" : "despesa") : null,
        valid ? tx.amount().abs().movePointRight(2).longValueExact() : null,
        ImportPlanner.title(tx));
  }
}
