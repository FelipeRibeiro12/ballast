package com.felipe.ballast.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "CadastroRequest")
public record RegisterRequest(
    @JsonProperty("email")
        @NotNull(message = EMAIL_MESSAGE)
        @Size(max = 254, message = EMAIL_MESSAGE)
        @BasicEmail(message = EMAIL_MESSAGE)
        @NoControlCharacters(message = EMAIL_MESSAGE)
        String email,
    @JsonProperty("senha")
        @NotNull(message = PASSWORD_MESSAGE)
        @Size(min = 12, max = 128, message = PASSWORD_MESSAGE)
        String password,
    @JsonProperty("nome")
        @Schema(maxLength = 100)
        @CodePointSize(max = 100, message = NAME_MESSAGE)
        @NoControlCharacters(message = NAME_MESSAGE)
        String name) {

  static final String EMAIL_MESSAGE = "e-mail inválido";
  static final String PASSWORD_MESSAGE = "senha deve ter entre 12 e 128 caracteres";
  static final String NAME_MESSAGE =
      "nome deve ter no máximo 100 caracteres e não pode ter caracteres de controle";

  // A migration tem CHECK de minúsculas: normalizar aqui (NFKC e minúscula) evita 500 e evita dois
  // cadastros que
  // diferem só pela caixa. Nome vazio vira nulo.
  public RegisterRequest {
    email = EmailNormalizer.normalize(email);
    name = name == null || name.isBlank() ? null : name.strip();
  }

  // O toString padrão do record imprimiria a senha em qualquer log que recebesse o objeto.
  @Override
  public String toString() {
    return "RegisterRequest[email=***, password=***, name=***]";
  }
}
