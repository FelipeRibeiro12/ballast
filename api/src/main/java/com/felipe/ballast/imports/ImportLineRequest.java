package com.felipe.ballast.imports;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(name = "ImportacaoLinhaRequest")
public record ImportLineRequest(
    @JsonProperty("acao")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"importar", "ignorar", "conciliar"})
        @NotNull(message = ACTION_MESSAGE)
        LineAction action,
    @JsonProperty("lancamentoId")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            format = "uuid",
            description = "Obrigatório com conciliar e proibido nas outras ações")
        String entryId,
    @JsonProperty("categoriaId")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            format = "uuid",
            description =
                "Só com importar. Sem ela vale Outros (despesa) ou Outras receitas (receita)")
        String categoryId,
    @JsonProperty("titulo")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            minLength = 1,
            maxLength = 100,
            description = "Só com importar. Sem ele fica o título que veio do arquivo")
        @CodePointSize(min = 1, max = 100, message = TITLE_MESSAGE)
        @NoControlCharacters(message = TITLE_MESSAGE)
        String title) {

  static final String ACTION_MESSAGE = "acao deve ser importar, ignorar ou conciliar";
  static final String TITLE_MESSAGE =
      "titulo deve ter entre 1 e 100 caracteres e não pode ter caracteres de controle";

  // O CHECK do banco recusa espaço nas pontas do título. Em branco vira ausente.
  public ImportLineRequest {
    title = title == null || title.isBlank() ? null : title.strip();
  }
}
