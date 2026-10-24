package com.felipe.ballast.entries;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

// Só texto e categoria: valor, parcelas, data e conta não se editam (ADR-032). Titulo e categoria
// ausentes ou nulos não mudam. Descrição ausente mantém; nula, vazia ou em branco limpa.
@Schema(name = "CompraParceladaAtualizarRequest")
public record InstallmentUpdateRequest(
    @JsonProperty("titulo")
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, minLength = 1, maxLength = 100)
        @CodePointSize(min = 1, max = 100, message = EntryRequest.TITLE_MESSAGE)
        @NoControlCharacters(message = EntryRequest.TITLE_MESSAGE)
        String title,
    @JsonProperty("descricao")
        @JsonDeserialize(using = ClearableText.class)
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            maxLength = 500,
            nullable = true,
            description = "Ausente mantém; nula, vazia ou em branco limpa a descrição")
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

  // Nulo explícito vira "" para se distinguir do campo ausente, que o Jackson deixa nulo no
  // record. O "" é o mesmo sinal de limpar que a descrição vazia já usa.
  static final class ClearableText extends StdDeserializer<String> {
    ClearableText() {
      super(String.class);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
      return context.readValue(parser, String.class);
    }

    @Override
    public String getNullValue(DeserializationContext context) {
      return "";
    }

    // O padrão do Jackson usa o valor nulo também para campo ausente.
    @Override
    public String getAbsentValue(DeserializationContext context) {
      return null;
    }
  }
}
