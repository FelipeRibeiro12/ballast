package com.felipe.ballast.wallets;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Campo não declarado é recusado como em qualquer corpo. A mensagem de validação é fixa para não
// devolver nonce nem assinatura.
@Schema(name = "VerificarPosseRequest")
public record PossessionVerifyRequest(
    @JsonProperty("nonce")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 64)
        @NotNull(message = MESSAGE)
        @Size(min = 1, max = 64, message = MESSAGE)
        String nonce,
    @JsonProperty("assinatura")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            maxLength = 200,
            description =
                "EVM: 0x mais 130 hexadecimais (r, s, v) do personal_sign. Solana: 64 bytes em"
                    + " base64")
        @NotNull(message = MESSAGE)
        @Size(min = 1, max = 200, message = MESSAGE)
        String signature) {

  static final String MESSAGE = "nonce e assinatura são obrigatórios e têm tamanho limitado";
}
