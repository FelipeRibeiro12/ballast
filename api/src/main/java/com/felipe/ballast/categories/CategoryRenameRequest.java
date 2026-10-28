package com.felipe.ballast.categories;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(name = "CategoriaRenomearRequest")
record CategoryRenameRequest(
    @JsonProperty("nome")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        @NotNull(message = CategoryRequest.NAME_MESSAGE)
        @NotBlank(message = CategoryRequest.NAME_MESSAGE)
        @CodePointSize(min = 1, max = 100, message = CategoryRequest.NAME_MESSAGE)
        @NoControlCharacters(message = CategoryRequest.NAME_MESSAGE)
        String name) {}
