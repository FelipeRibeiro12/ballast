package com.felipe.ballast.fixedincome;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.entries.IsoDates;
import com.felipe.ballast.entries.ValidIsoDate;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(name = "TituloRendaFixaRequest")
public record TitleRequest(
    @JsonProperty("contaId")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        @NotNull(message = "contaId é obrigatório")
        String accountId,
    @JsonProperty("tipo")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "tipo deve ser lci, lca ou cdb")
        FixedIncomeType type,
    @JsonProperty("nome")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        @NotNull(message = NAME_MESSAGE)
        @CodePointSize(min = 1, max = 100, message = NAME_MESSAGE)
        @NoControlCharacters(message = NAME_MESSAGE)
        String name,
    @JsonProperty("valorAplicadoCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "1",
            maximum = "9007199254740991")
        @NotNull(message = AMOUNT_MESSAGE)
        @Min(value = 1, message = AMOUNT_MESSAGE)
        @Max(value = MAX_CENTS, message = AMOUNT_MESSAGE)
        Long investedCents,
    @JsonProperty("dataAplicacao")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        @NotNull(message = DATE_MESSAGE)
        @ValidIsoDate(message = DATE_MESSAGE)
        String applicationDate,
    @JsonProperty("vencimento")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            type = "string",
            format = "date")
        @ValidIsoDate(message = MATURITY_MESSAGE)
        String maturity,
    @JsonProperty("indexador")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "indexador deve ser prefixado, cdi ou ipca")
        Indexer indexer,
    @JsonProperty("taxa")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Em texto. Prefixado: % ao ano. CDI: % do CDI. IPCA: % ao ano acima do IPCA",
            example = "95",
            pattern = RATE_PATTERN)
        @NotNull(message = RATE_MESSAGE)
        @Pattern(regexp = RATE_PATTERN, message = RATE_MESSAGE)
        String rate) {

  // Number.MAX_SAFE_INTEGER: acima disso o JavaScript perde o centavo (ADR-023).
  static final long MAX_CENTS = 9_007_199_254_740_991L;

  // numeric(9,4): até 5 dígitos inteiros e 4 casas, maior que zero.
  static final String RATE_PATTERN = "(?=.*[1-9])[0-9]{1,5}(\\.[0-9]{1,4})?";

  static final String NAME_MESSAGE =
      "nome deve ter entre 1 e 100 caracteres e não pode ter caracteres de controle";
  static final String AMOUNT_MESSAGE =
      "valorAplicadoCentavos deve ser um inteiro entre 1 e 9007199254740991";
  static final String DATE_MESSAGE =
      "dataAplicacao deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199";
  static final String MATURITY_MESSAGE =
      "vencimento deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199";
  static final String RATE_MESSAGE =
      "taxa deve ser um decimal maior que zero, em texto, com até 5 dígitos inteiros e 4 casas";

  // O CHECK do banco recusa espaço nas pontas. Vencimento em branco é sem vencimento.
  public TitleRequest {
    name = name == null ? null : name.strip();
    maturity = maturity == null || maturity.isBlank() ? null : maturity;
  }

  BigDecimal invested() {
    return BigDecimal.valueOf(investedCents, 2);
  }

  LocalDate parsedApplicationDate() {
    return IsoDates.parse(applicationDate).orElseThrow();
  }

  LocalDate parsedMaturity() {
    return maturity == null ? null : IsoDates.parse(maturity).orElseThrow();
  }

  BigDecimal parsedRate() {
    return new BigDecimal(rate);
  }
}
