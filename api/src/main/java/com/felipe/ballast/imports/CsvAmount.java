package com.felipe.ballast.imports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

// Lê o valor de uma célula de CSV direto de String para BigDecimal, sem double. Não adivinha: na
// dúvida a linha é inválida e o usuário vê o motivo na revisão.
final class CsvAmount {

  record Parsed(BigDecimal value, String error) {}

  // Teto da coluna valor: o mesmo do CHECK lancamento_valor_faixa.
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("90071992547409.91");

  // O separador de milhar só vale em grupos de 3 dígitos. Com decimal=virgula, "12.5" não é 12,5:
  // o ponto é milhar e o grupo está errado, então a linha é inválida em vez de virar outro valor.
  private static final Pattern COMMA_STYLE = Pattern.compile("(\\d{1,3}(\\.\\d{3})+|\\d+)(,\\d+)?");
  private static final Pattern DOT_STYLE = Pattern.compile("(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d+)?");

  private static final Parsed INVALID = new Parsed(null, "valor inválido");

  private CsvAmount() {}

  static Parsed parse(String raw, CsvMapping.Decimal decimal) {
    if (raw == null) {
      return INVALID;
    }
    String text = withoutSpaces(raw);
    boolean negative = false;
    // Aceita "-R$ 12,00" e "R$ -12,00". Parênteses não: cada banco usa de um jeito.
    if (text.startsWith("R$")) {
      text = text.substring(2);
    }
    if (text.startsWith("-") || text.startsWith("+")) {
      negative = text.charAt(0) == '-';
      text = text.substring(1);
      if (text.startsWith("R$")) {
        text = text.substring(2);
      }
    }
    boolean comma = decimal == CsvMapping.Decimal.COMMA;
    if (!(comma ? COMMA_STYLE : DOT_STYLE).matcher(text).matches()) {
      return INVALID;
    }
    String plain = comma ? text.replace(".", "").replace(',', '.') : text.replace(",", "");
    BigDecimal parsed = new BigDecimal(plain).stripTrailingZeros();
    if (parsed.signum() == 0) {
      return new Parsed(null, "valor zero");
    }
    if (parsed.scale() > 2) {
      return new Parsed(null, "valor com mais de 2 casas decimais");
    }
    if (parsed.compareTo(MAX_AMOUNT) > 0) {
      return new Parsed(null, "valor fora do limite");
    }
    // UNNECESSARY: a escala já é no máximo 2, então nada é arredondado.
    BigDecimal value = parsed.setScale(2, RoundingMode.UNNECESSARY);
    return new Parsed(negative ? value.negate() : value, null);
  }

  private static String withoutSpaces(String raw) {
    StringBuilder builder = new StringBuilder(raw.length());
    raw.codePoints()
        .filter(point -> !Character.isWhitespace(point) && !Character.isSpaceChar(point))
        .forEach(builder::appendCodePoint);
    return builder.toString();
  }
}
