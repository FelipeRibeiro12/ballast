package com.felipe.ballast.budgets;

import java.time.YearMonth;
import java.util.Optional;
import java.util.regex.Pattern;

// Mês da API: AAAA-MM estrito, ano de 1900 a 2199, a faixa do CHECK da V18.
final class Months {

  static final String SHAPE = "(19[0-9]{2}|2[01][0-9]{2})-(0[1-9]|1[0-2])";

  private static final Pattern PATTERN = Pattern.compile(SHAPE);

  private Months() {}

  static Optional<YearMonth> parse(String text) {
    if (text == null || !PATTERN.matcher(text).matches()) {
      return Optional.empty();
    }
    return Optional.of(YearMonth.parse(text));
  }
}
