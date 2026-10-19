package com.felipe.ballast.web;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

// UUID.fromString também lê "1-1-1-1-1" e outras formas curtas; só a forma de 36 caracteres vale.
public final class CanonicalUuid {

  private static final Pattern PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private CanonicalUuid() {}

  public static Optional<UUID> parse(String text) {
    if (text == null || !PATTERN.matcher(text).matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(text));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
