package com.felipe.ballast.categories;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(name = "CategoriaRequest")
record CategoryRequest(
    @JsonProperty("nome")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        @NotNull(message = NAME_MESSAGE)
        @NotBlank(message = NAME_MESSAGE)
        @CodePointSize(min = 1, max = 100, message = NAME_MESSAGE)
        @NoControlCharacters(message = NAME_MESSAGE)
        String name,
    @JsonProperty("tipo")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Só no cadastro; o tipo não muda depois")
        @NotNull(message = "tipo deve ser receita ou despesa")
        CategoryType type) {

  static final String NAME_MESSAGE =
      "nome deve ter entre 1 e 100 caracteres e não pode ter caracteres de controle";
}
