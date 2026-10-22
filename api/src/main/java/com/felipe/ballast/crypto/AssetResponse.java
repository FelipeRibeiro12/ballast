package com.felipe.ballast.crypto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "AtivoCripto")
public record AssetResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CryptoNetwork rede,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Nulo no ativo nativo; em minúsculas nas redes EVM")
        String contrato,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 20)
        String simbolo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 100)
        String nome,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "18")
        int decimais,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Sempre true no ativo digitado pelo usuário")
        boolean confiavel) {

  static AssetResponse from(CryptoQueries.Asset asset) {
    return new AssetResponse(
        asset.id(),
        asset.network(),
        asset.contract(),
        asset.symbol(),
        asset.name(),
        asset.decimals(),
        asset.trusted());
  }
}
