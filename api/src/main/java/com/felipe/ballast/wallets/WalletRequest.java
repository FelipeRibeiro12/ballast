package com.felipe.ballast.wallets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Campo não declarado é recusado como em qualquer corpo. Nenhuma mensagem repete o valor enviado:
// o endereço não pode voltar nem em erro de validação.
@Schema(name = "CarteiraRequest")
public record WalletRequest(
    @JsonProperty("contaId")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        @NotNull(message = "contaId é obrigatório")
        String accountId,
    @JsonProperty("rede")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"ethereum", "base", "solana"})
        @NotNull(message = NETWORK_MESSAGE)
        @Pattern(regexp = "ethereum|base|solana", message = NETWORK_MESSAGE)
        String network,
    @JsonProperty("endereco")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            maxLength = 100,
            description =
                "EVM: 0x mais 40 hexadecimais, com checksum EIP-55 se tiver caixa mista, gravado"
                    + " em minúsculas. Solana: base58 que decodifica em 32 bytes")
        @NotNull(message = ADDRESS_MESSAGE)
        @Size(min = 1, max = 100, message = ADDRESS_MESSAGE)
        String address,
    @JsonProperty("rotulo")
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 60)
        @CodePointSize(max = 60, message = LABEL_MESSAGE)
        @NoControlCharacters(message = LABEL_MESSAGE)
        String label) {

  static final String NETWORK_MESSAGE = "rede deve ser ethereum, base ou solana";
  static final String ADDRESS_MESSAGE = "endereco deve ter entre 1 e 100 caracteres";
  static final String LABEL_MESSAGE =
      "rotulo deve ter no máximo 60 caracteres e não pode ter caracteres de controle";

  // Endereço colado costuma vir com espaço nas pontas. Rótulo em branco vira nulo, e o CHECK do
  // banco recusa espaço nas pontas.
  public WalletRequest {
    address = address == null ? null : address.strip();
    label = label == null || label.isBlank() ? null : label.strip();
  }

  WalletNetwork parsedNetwork() {
    return WalletNetwork.fromValue(network).orElseThrow();
  }
}
