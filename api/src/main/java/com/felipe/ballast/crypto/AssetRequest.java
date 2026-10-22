package com.felipe.ballast.crypto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(name = "AtivoCriptoRequest")
public record AssetRequest(
    @JsonProperty("rede")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = NETWORK_MESSAGE)
        CryptoNetwork network,
    @JsonProperty("contrato")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description =
                "Ausente ou nulo é o ativo nativo da rede. EVM: 0x mais 40 hexadecimais, gravado"
                    + " em minúsculas. Solana: base58 de 32 a 44. Bitcoin: sempre nulo")
        String contract,
    @JsonProperty("simbolo")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 20)
        @NotNull(message = SYMBOL_MESSAGE)
        @CodePointSize(min = 1, max = 20, message = SYMBOL_MESSAGE)
        @NoControlCharacters(message = SYMBOL_MESSAGE)
        String symbol,
    @JsonProperty("nome")
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 100)
        @CodePointSize(max = 100, message = NAME_MESSAGE)
        @NoControlCharacters(message = NAME_MESSAGE)
        String name,
    @JsonProperty("decimais")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "18")
        @NotNull(message = DECIMALS_MESSAGE)
        @Min(value = 0, message = DECIMALS_MESSAGE)
        @Max(value = 18, message = DECIMALS_MESSAGE)
        Integer decimals) {

  static final String NETWORK_MESSAGE =
      "rede deve ser bitcoin, ethereum, base, arbitrum, polygon ou solana";
  static final String SYMBOL_MESSAGE =
      "simbolo deve ter entre 1 e 20 caracteres e não pode ter caracteres de controle";
  static final String NAME_MESSAGE =
      "nome deve ter no máximo 100 caracteres e não pode ter caracteres de controle";
  static final String DECIMALS_MESSAGE = "decimais deve ser um inteiro entre 0 e 18";

  // O CHECK do banco recusa espaço nas pontas do símbolo. Nome em branco vira nulo, como a
  // descrição do lançamento.
  public AssetRequest {
    symbol = symbol == null ? null : symbol.strip();
    name = name == null || name.isBlank() ? null : name.strip();
  }
}
