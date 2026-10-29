package com.felipe.ballast.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.imports.CsvMapping.Decimal;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CsvAmountTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "1.234,56|1234.56",
        "-1.234,56|-1234.56",
        "+12,5|12.50",
        "12|12.00",
        "12,5|12.50",
        "0,05|0.05",
        "1.234.567,89|1234567.89",
        "R$ 1.234,56|1234.56",
        "-R$ 12,00|-12.00",
        "R$ -12,00|-12.00",
        "12,500|12.50"
      })
  void readsBrazilianDecimals(String raw, String expected) {
    CsvAmount.Parsed parsed = CsvAmount.parse(raw, Decimal.COMMA);

    assertThat(parsed.error()).isNull();
    assertThat(parsed.value()).isEqualTo(new BigDecimal(expected));
    assertThat(parsed.value().scale()).isEqualTo(2);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"1,234.56|1234.56", "-1,234.56|-1234.56", "12.5|12.50", "1,234|1234.00", "7|7.00"})
  void readsDotDecimals(String raw, String expected) {
    CsvAmount.Parsed parsed = CsvAmount.parse(raw, Decimal.DOT);

    assertThat(parsed.error()).isNull();
    assertThat(parsed.value()).isEqualTo(new BigDecimal(expected));
  }

  @Test
  void nonBreakingSpacesAreIgnored() {
    assertThat(CsvAmount.parse("R$ 1.234,56", Decimal.COMMA).value())
        .isEqualTo(new BigDecimal("1234.56"));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "12,345|valor com mais de 2 casas decimais",
        "-0,00|valor zero",
        "0|valor zero",
        "(12,00)|valor inválido",
        "abc|valor inválido",
        "12.5|valor inválido",
        "1.23|valor inválido",
        "12,|valor inválido",
        ",5|valor inválido",
        "1,2,3|valor inválido",
        "--5|valor inválido",
        "1e3|valor inválido",
        "90.071.992.547.409,92|valor fora do limite"
      })
  void refusesWhatItCannotReadWithoutGuessing(String raw, String reason) {
    CsvAmount.Parsed parsed = CsvAmount.parse(raw, Decimal.COMMA);

    assertThat(parsed.value()).isNull();
    assertThat(parsed.error()).isEqualTo(reason);
  }

  @Test
  void blankAndNullAreInvalid() {
    assertThat(CsvAmount.parse("", Decimal.COMMA).error()).isEqualTo("valor inválido");
    assertThat(CsvAmount.parse("   ", Decimal.COMMA).error()).isEqualTo("valor inválido");
    assertThat(CsvAmount.parse(null, Decimal.COMMA).error()).isEqualTo("valor inválido");
  }

  @Test
  void dotStyleRefusesCommaDecimal() {
    assertThat(CsvAmount.parse("12,50", Decimal.DOT).error()).isEqualTo("valor inválido");
  }

  @Test
  void largestAllowedValueIsAccepted() {
    assertThat(CsvAmount.parse("90.071.992.547.409,91", Decimal.COMMA).value())
        .isEqualTo(new BigDecimal("90071992547409.91"));
  }
}
