package com.felipe.ballast.crypto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// Rede e contrato são a identidade do ativo e não mudam: campo a mais é recusado como em qualquer
// corpo. Para trocar de rede, apague e cadastre de novo.
@Schema(name = "AtivoCriptoEdicao")
public record AssetUpdateRequest(
    @JsonProperty("simbolo")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 20)
        @NotNull(message = AssetRequest.SYMBOL_MESSAGE)
        @CodePointSize(min = 1, max = 20, message = AssetRequest.SYMBOL_MESSAGE)
        @NoControlCharacters(message = AssetRequest.SYMBOL_MESSAGE)
        String symbol,
    @JsonProperty("nome")
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 100)
        @CodePointSize(max = 100, message = AssetRequest.NAME_MESSAGE)
        @NoControlCharacters(message = AssetRequest.NAME_MESSAGE)
        String name,
    @JsonProperty("decimais")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "18")
        @NotNull(message = AssetRequest.DECIMALS_MESSAGE)
        @Min(value = 0, message = AssetRequest.DECIMALS_MESSAGE)
        @Max(value = 18, message = AssetRequest.DECIMALS_MESSAGE)
        Integer decimals) {

  // Mesmo tratamento do cadastro: o CHECK do banco recusa espaço nas pontas do símbolo.
  public AssetUpdateRequest {
    symbol = symbol == null ? null : symbol.strip();
    name = name == null || name.isBlank() ? null : name.strip();
  }
}
