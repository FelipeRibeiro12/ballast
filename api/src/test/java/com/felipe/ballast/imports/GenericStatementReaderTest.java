package com.felipe.ballast.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenericStatementReaderTest {

  private final GenericStatementReader reader = new GenericStatementReader();

  private List<OfxTransaction> read(String text, LocalDate today) {
    return reader.read(text, today).transactions();
  }

  @Test
  void decemberPurchaseOnAJanuaryStatementBelongsToThePreviousYear() {
    List<OfxTransaction> rows =
        read(
            "Vencimento 20/01/2027\n28/12 LOJA 10,00\n05/01 OUTRA 20,00",
            LocalDate.of(2027, 1, 10));

    assertThat(rows.get(0).date()).isEqualTo(LocalDate.of(2026, 12, 28));
    assertThat(rows.get(1).date()).isEqualTo(LocalDate.of(2027, 1, 5));
  }

  @Test
  void withoutAReferenceDateTheTodayYearAppliesAndFutureDaysFallBack() {
    List<OfxTransaction> rows = read("15/03 A 1,00\n15/11 B 2,00", LocalDate.of(2026, 4, 1));

    assertThat(rows.get(0).date()).isEqualTo(LocalDate.of(2026, 3, 15));
    assertThat(rows.get(1).date()).isEqualTo(LocalDate.of(2025, 11, 15));
  }

  @Test
  void leapDayFallsBackToTheLeapYearAndImpossibleDaysAreInvalid() {
    List<OfxTransaction> rows =
        read("29/02 BISSEXTO 5,00\n31/02 IMPOSSIVEL 5,00", LocalDate.of(2025, 3, 10));

    assertThat(rows.get(0).date()).isEqualTo(LocalDate.of(2024, 2, 29));
    assertThat(rows.get(1).invalidReason()).isEqualTo("data inválida");
  }

  @Test
  void amountsKeepScaleAndSignComesFromCr() {
    List<OfxTransaction> rows =
        read(
            "05/10/2026 A 1.234,50\n06/10/2026 B 0,07 CR\n07/10/2026 C -3,00",
            LocalDate.of(2026, 10, 29));

    assertThat(rows.get(0).amount()).isEqualByComparingTo(new BigDecimal("-1234.50"));
    assertThat(rows.get(0).amount().scale()).isEqualTo(2);
    assertThat(rows.get(1).amount()).isEqualByComparingTo(new BigDecimal("0.07"));
    assertThat(rows.get(2).amount()).isEqualByComparingTo(new BigDecimal("3.00"));
  }

  @Test
  void installmentIsKeptAndAZeroValueIsInvalid() {
    List<OfxTransaction> rows =
        read("05/10/2026 TV 2/12 99,90\n05/10/2026 ZERO 0,00", LocalDate.of(2026, 10, 29));

    assertThat(rows.get(0).raw()).containsEntry("parcela", "2/12");
    assertThat(rows.get(1).invalidReason()).isEqualTo("valor zero");
  }

  @Test
  void hashesAreStablePerOccurrenceAndDifferByAccount() {
    PdfFile file =
        reader.read("05/10/2026 CAFE 10,00\n05/10/2026 cafe  10,00", LocalDate.of(2026, 10, 29));
    java.util.UUID a = java.util.UUID.randomUUID();

    List<String> first = file.hashes(a);

    assertThat(first.get(0)).isNotEqualTo(first.get(1));
    assertThat(file.hashes(a)).isEqualTo(first);
    assertThat(file.hashes(java.util.UUID.randomUUID())).isNotEqualTo(first);
  }
}
