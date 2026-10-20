package com.felipe.ballast.entries;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

// Data da API: AAAA-MM-DD estrito, ano de 1900 a 2199 (a faixa do CHECK do banco). O formato é
// conferido antes do parse porque o parser do java.time aceitaria, por exemplo, ano com sinal.
public final class IsoDates {

  private static final Pattern SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

  private static final int MIN_YEAR = 1900;
  private static final int MAX_YEAR = 2199;

  private IsoDates() {}

  // O ISO_LOCAL_DATE resolve em modo estrito: 2026-02-30 não vira 28 de fevereiro.
  public static Optional<LocalDate> parse(String text) {
    if (text == null || !SHAPE.matcher(text).matches()) {
      return Optional.empty();
    }
    try {
      LocalDate date = LocalDate.parse(text);
      boolean inRange = date.getYear() >= MIN_YEAR && date.getYear() <= MAX_YEAR;
      return inRange ? Optional.of(date) : Optional.empty();
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
