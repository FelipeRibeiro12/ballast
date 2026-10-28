package com.felipe.ballast.categories;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "Categoria")
public record CategoryResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        String nome,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CategoryType tipo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID paiId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 9) String cor,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 50)
        String icone,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Outros e Outras receitas: não renomeia nem apaga")
        boolean protegida) {

  static CategoryResponse from(Category category) {
    return new CategoryResponse(
        category.getId(),
        category.getName(),
        category.getType(),
        category.getParentId(),
        category.getColor(),
        category.getIcon(),
        category.isProtected());
  }
}
