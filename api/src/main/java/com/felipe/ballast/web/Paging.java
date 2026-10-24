package com.felipe.ballast.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.OptionalInt;

// Paginação por cursor das listagens (ADR-056). O corpo continua sendo o array de sempre; o
// cursor da próxima página vai no cabeçalho X-Next-Cursor, só quando há mais itens.
public final class Paging {

  public static final String NEXT_CURSOR_HEADER = "X-Next-Cursor";
  public static final ErrorResponse INVALID_LIMIT =
      new ErrorResponse("limite deve ser um inteiro de 1 a 1000");
  public static final ErrorResponse INVALID_CURSOR = new ErrorResponse("cursor inválido");

  // O padrão é o teto: quem não manda limite recebe tudo até 1000, como antes até o uso atual.
  public static final int MAX_LIMIT = 1000;

  // Cursor legítimo tem uns 100 caracteres; o teto barra texto grande antes de decodificar.
  private static final int MAX_CURSOR_LENGTH = 300;
  private static final char SEPARATOR = '|';

  private Paging() {}

  // Nulo vale o padrão. Texto, e não int, para decidir a mensagem aqui: o conversor do Spring
  // daria um erro de tipo sem o texto do contrato.
  public static OptionalInt parseLimit(String raw) {
    if (raw == null) {
      return OptionalInt.of(MAX_LIMIT);
    }
    if (raw.isEmpty() || raw.length() > 4) {
      return OptionalInt.empty();
    }
    for (int i = 0; i < raw.length(); i++) {
      if (raw.charAt(i) < '0' || raw.charAt(i) > '9') {
        return OptionalInt.empty();
      }
    }
    int limit = Integer.parseInt(raw);
    return limit >= 1 && limit <= MAX_LIMIT ? OptionalInt.of(limit) : OptionalInt.empty();
  }

  public static String encode(String... parts) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            String.join(String.valueOf(SEPARATOR), parts).getBytes(StandardCharsets.UTF_8));
  }

  // Vazio quando não é base64 válido ou não tem exatamente o número de partes esperado. Quem
  // chama ainda valida cada parte: o cursor vem do cliente e pode ser qualquer texto.
  public static Optional<String[]> decode(String cursor, int expectedParts) {
    if (cursor.isEmpty() || cursor.length() > MAX_CURSOR_LENGTH) {
      return Optional.empty();
    }
    try {
      String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = text.split("\\|", -1);
      return parts.length == expectedParts ? Optional.of(parts) : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
