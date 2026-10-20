package com.felipe.ballast.entries;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;

// Só texto e categoria: valor, parcelas, data e conta não se editam (ADR-032). Campo ausente ou
// nulo não muda; descrição em branco limpa a descrição.
@Schema(name = "CompraParceladaAtualizarRequest")
public record InstallmentUpdateRequest(
    @JsonProperty("titulo")
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, minLength = 1, maxLength = 100)
        @CodePointSize(min = 1, max = 100, message = EntryRequest.TITLE_MESSAGE)
        @NoControlCharacters(message = EntryRequest.TITLE_MESSAGE)
        String title,
    @JsonProperty("descricao")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            maxLength = 500,
            description = "Vazia ou em branco limpa a descrição")
        @CodePointSize(max = 500, message = EntryRequest.DESCRIPTION_MESSAGE)
        @NoControlCharacters(message = EntryRequest.DESCRIPTION_MESSAGE)
        String description,
    @JsonProperty("categoriaId")
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, format = "uuid")
        String categoryId) {

  public InstallmentUpdateRequest {
    title = title == null ? null : title.strip();
    description = description == null ? null : description.strip();
  }

  boolean isEmpty() {
    return title == null && description == null && categoryId == null;
  }
}
