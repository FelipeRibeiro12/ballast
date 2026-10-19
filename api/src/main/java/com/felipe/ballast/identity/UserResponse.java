package com.felipe.ballast.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "Usuario")
public record UserResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 254) String email,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 100)
        String nome) {

  static UserResponse from(UserProfile profile) {
    return new UserResponse(profile.id(), profile.email(), profile.name());
  }
}
