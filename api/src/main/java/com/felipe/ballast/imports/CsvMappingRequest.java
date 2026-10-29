package com.felipe.ballast.imports;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// Campo não declarado é recusado pelo Jackson (fail-on-unknown-properties). Que as colunas sejam
// distintas entre si o controller confere, porque depende de mais de um campo.
@Schema(name = "MapeamentoCsvRequest")
public record CsvMappingRequest(
    @JsonProperty("nome")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            minLength = 1,
            maxLength = 60,
            description = "Banco ou apelido; único por usuário")
        @NotNull(message = NAME_MESSAGE)
        @NotBlank(message = NAME_MESSAGE)
        @CodePointSize(min = 1, max = 60, message = NAME_MESSAGE)
        @NoControlCharacters(message = NAME_MESSAGE)
        String name,
    @JsonProperty("separador")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "separador deve ser ponto_e_virgula, virgula, tab ou barra_vertical")
        CsvMapping.Separator separator,
    @JsonProperty("formatoData")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "formatoData deve ser dd/MM/yyyy, yyyy-MM-dd, MM/dd/yyyy ou dd-MM-yyyy")
        CsvMapping.DateFormat dateFormat,
    @JsonProperty("decimal")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "decimal deve ser virgula ou ponto")
        CsvMapping.Decimal decimal,
    @JsonProperty("sinal")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "sinal deve ser negativo_despesa ou positivo_despesa")
        CsvMapping.Sign sign,
    @JsonProperty("temCabecalho")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "temCabecalho é obrigatório")
        Boolean hasHeader,
    @JsonProperty("colunaData")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = COLUMN_DESCRIPTION)
        @NotNull(message = COLUMN_MESSAGE)
        @Min(value = 0, message = COLUMN_MESSAGE)
        @Max(value = CsvMapping.MAX_COLUMN_INDEX, message = COLUMN_MESSAGE)
        Integer dateColumn,
    @JsonProperty("colunaValor")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = COLUMN_DESCRIPTION)
        @NotNull(message = COLUMN_MESSAGE)
        @Min(value = 0, message = COLUMN_MESSAGE)
        @Max(value = CsvMapping.MAX_COLUMN_INDEX, message = COLUMN_MESSAGE)
        Integer amountColumn,
    @JsonProperty("colunaDescricao")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = COLUMN_DESCRIPTION)
        @NotNull(message = COLUMN_MESSAGE)
        @Min(value = 0, message = COLUMN_MESSAGE)
        @Max(value = CsvMapping.MAX_COLUMN_INDEX, message = COLUMN_MESSAGE)
        Integer descriptionColumn,
    @JsonProperty("colunaId")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description =
                COLUMN_DESCRIPTION + ". Coluna com o identificador da transação, se o banco tiver")
        @Min(value = 0, message = COLUMN_MESSAGE)
        @Max(value = CsvMapping.MAX_COLUMN_INDEX, message = COLUMN_MESSAGE)
        Integer idColumn) {

  static final String NAME_MESSAGE =
      "nome deve ter entre 1 e 60 caracteres e não pode ter caracteres de controle";
  static final String COLUMN_MESSAGE = "as colunas devem ser índices de 0 a 49";
  static final String COLUMN_DESCRIPTION = "Índice da coluna, a partir de 0, de 0 a 49";

  // O CHECK do banco recusa espaço nas pontas do nome.
  public CsvMappingRequest {
    name = name == null ? null : name.strip();
  }

  CsvMapping toMapping() {
    return new CsvMapping(
        separator,
        dateFormat,
        decimal,
        sign,
        hasHeader,
        dateColumn,
        amountColumn,
        descriptionColumn,
        idColumn);
  }
}
