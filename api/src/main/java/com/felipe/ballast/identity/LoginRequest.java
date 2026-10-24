package com.felipe.ballast.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "LoginRequest")
public record LoginRequest(
    @JsonProperty("email")
        @NotNull(message = MESSAGE)
        @Size(max = 254, message = MESSAGE)
        @BasicEmail(message = MESSAGE)
        @NoControlCharacters(message = MESSAGE)
        String email,
    // A política de tamanho mínimo é do cadastro; aqui só o teto, contra DoS no argon2.
    @JsonProperty("senha") @NotNull(message = MESSAGE) @Size(min = 1, max = 128, message = MESSAGE)
        String password) {

  static final String MESSAGE = "e-mail ou senha inválidos";

  public LoginRequest {
    email = EmailNormalizer.normalize(email);
  }

  @Override
  public String toString() {
    return "LoginRequest[email=***, password=***]";
  }
}
