package com.felipe.ballast.imports;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.format.DateTimeFormatter;
import java.time.format.DecimalStyle;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Optional;

// Como ler as colunas de um CSV de banco. Os nomes do JSON e da query são os mesmos valores dos
// CHECKs da V35. Os índices de coluna começam em 0.
record CsvMapping(
    Separator separator,
    DateFormat dateFormat,
    Decimal decimal,
    Sign sign,
    boolean hasHeader,
    int dateColumn,
    int amountColumn,
    int descriptionColumn,
    Integer idColumn) {

  static final int MAX_COLUMN_INDEX = 49;

  enum Separator {
    SEMICOLON("ponto_e_virgula", ';'),
    COMMA("virgula", ','),
    TAB("tab", '\t'),
    PIPE("barra_vertical", '|');

    private final String value;
    private final char character;

    Separator(String value, char character) {
      this.value = value;
      this.character = character;
    }

    @JsonValue
    public String value() {
      return value;
    }

    char character() {
      return character;
    }

    static Optional<Separator> fromValue(String value) {
      return Arrays.stream(values()).filter(s -> s.value.equals(value)).findFirst();
    }
  }

  // O padrão usa uuuu e não yyyy: com ResolverStyle.STRICT o yyyy (ano da era) exige a era e
  // recusa qualquer data.
  enum DateFormat {
    DAY_MONTH_YEAR_SLASH("dd/MM/yyyy", "dd/MM/uuuu"),
    YEAR_MONTH_DAY_DASH("yyyy-MM-dd", "uuuu-MM-dd"),
    MONTH_DAY_YEAR_SLASH("MM/dd/yyyy", "MM/dd/uuuu"),
    DAY_MONTH_YEAR_DASH("dd-MM-yyyy", "dd-MM-uuuu");

    private final String value;
    private final DateTimeFormatter formatter;

    DateFormat(String value, String pattern) {
      this.value = value;
      this.formatter =
          DateTimeFormatter.ofPattern(pattern)
              .withResolverStyle(ResolverStyle.STRICT)
              .withDecimalStyle(DecimalStyle.STANDARD);
    }

    @JsonValue
    public String value() {
      return value;
    }

    DateTimeFormatter formatter() {
      return formatter;
    }

    static Optional<DateFormat> fromValue(String value) {
      return Arrays.stream(values()).filter(f -> f.value.equals(value)).findFirst();
    }
  }

  enum Decimal {
    COMMA("virgula"),
    DOT("ponto");

    private final String value;

    Decimal(String value) {
      this.value = value;
    }

    @JsonValue
    public String value() {
      return value;
    }

    static Optional<Decimal> fromValue(String value) {
      return Arrays.stream(values()).filter(d -> d.value.equals(value)).findFirst();
    }
  }

  // positivo_despesa é o extrato de cartão, onde a compra vem positiva.
  enum Sign {
    NEGATIVE_EXPENSE("negativo_despesa"),
    POSITIVE_EXPENSE("positivo_despesa");

    private final String value;

    Sign(String value) {
      this.value = value;
    }

    @JsonValue
    public String value() {
      return value;
    }

    static Optional<Sign> fromValue(String value) {
      return Arrays.stream(values()).filter(s -> s.value.equals(value)).findFirst();
    }
  }

  // Cada coluna mapeada precisa de um índice próprio, senão uma mesma célula valeria por duas.
  boolean hasDistinctColumns() {
    return dateColumn != amountColumn
        && dateColumn != descriptionColumn
        && amountColumn != descriptionColumn
        && (idColumn == null
            || (idColumn != dateColumn
                && idColumn != amountColumn
                && idColumn != descriptionColumn));
  }

  int highestColumn() {
    int highest = Math.max(dateColumn, Math.max(amountColumn, descriptionColumn));
    return idColumn == null ? highest : Math.max(highest, idColumn);
  }
}
