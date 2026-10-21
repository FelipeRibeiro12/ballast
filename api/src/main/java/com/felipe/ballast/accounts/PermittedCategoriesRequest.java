package com.felipe.ballast.accounts;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(name = "CategoriasPermitidasRequest")
public record PermittedCategoriesRequest(
    @JsonProperty("categoriaIds")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Lista inteira que passa a valer. Vazia libera qualquer categoria de despesa")
        @NotNull(message = "categoriaIds é obrigatório")
        @Size(max = PermittedCategories.MAX, message = PermittedCategories.SIZE_MESSAGE)
        List<String> categoryIds) {}
