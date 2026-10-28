package com.felipe.ballast.thirdparties;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(name = "TerceiroRequest")
record ThirdPartyRequest(
    @JsonProperty("nome")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        @NotNull(message = NAME_MESSAGE)
        @NotBlank(message = NAME_MESSAGE)
        @CodePointSize(min = 1, max = 100, message = NAME_MESSAGE)
        @NoControlCharacters(message = NAME_MESSAGE)
        String name,
    @JsonProperty("arquivado")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            defaultValue = "false",
            description = "Só no PUT. Terceiro arquivado não recebe compra nova")
        Boolean archived) {

  static final String NAME_MESSAGE =
      "nome deve ter entre 1 e 100 caracteres e não pode ter caracteres de controle";

  boolean archivedOrDefault() {
    return archived != null && archived;
  }
}
