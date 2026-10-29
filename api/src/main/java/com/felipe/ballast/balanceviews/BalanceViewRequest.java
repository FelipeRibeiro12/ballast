package com.felipe.ballast.balanceviews;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.accounts.AccountType;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(name = "VisaoSaldoRequest")
record BalanceViewRequest(
    @JsonProperty("titulo")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        @NotNull(message = TITLE_MESSAGE)
        @NotBlank(message = TITLE_MESSAGE)
        @CodePointSize(min = 1, max = 100, message = TITLE_MESSAGE)
        @NoControlCharacters(message = TITLE_MESSAGE)
        String title,
    @JsonProperty("tipos")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "De 1 a 8 tipos de conta que o cartão soma")
        @NotNull(message = TYPES_MESSAGE)
        @Size(min = 1, max = 8, message = TYPES_MESSAGE)
        List<@NotNull(message = TYPES_MESSAGE) AccountType> types) {

  static final String TITLE_MESSAGE =
      "titulo deve ter entre 1 e 100 caracteres e não pode ter caracteres de controle";
  static final String TYPES_MESSAGE = "tipos deve ter de 1 a 8 tipos de conta válidos";
}
