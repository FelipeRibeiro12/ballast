package com.felipe.ballast.recurrences;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

// Regra do centavo (dados.md): cada parcela por percentual é truncada e a última recebe o resto.
// Compara em texto porque a escala conta: 333.30 e 333.3 são o mesmo número mas não o mesmo valor.
class SalarySplitTest {

  private static Division percent(int... centesimos) {
    List<DivisionPart> parts = new ArrayList<>();
    for (int i = 0; i < centesimos.length; i++) {
      parts.add(new DivisionPart(i + 1, i + 1, BigDecimal.valueOf(centesimos[i], 2), null));
    }
    return new Division(DivisionMode.PERCENT, parts);
  }

  private static Division fixed(long... cents) {
    List<DivisionPart> parts = new ArrayList<>();
    for (int i = 0; i < cents.length; i++) {
      parts.add(new DivisionPart(i + 1, i + 1, null, BigDecimal.valueOf(cents[i], 2)));
    }
    return new Division(DivisionMode.AMOUNT, parts);
  }

  private static BigDecimal reais(String value) {
    return new BigDecimal(value);
  }

  private static List<String> split(String total, Division division) {
    return SalarySplit.amounts(reais(total), division).stream()
        .map(BigDecimal::toPlainString)
        .toList();
  }

  private static BigDecimal sum(List<BigDecimal> amounts) {
    return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  @Test
  void thousandInThreeParts3333_3333_3334GivesTheRemainderToTheLast() {
    // 3333 + 3333 + 3334 = 10000, a divisão válida em três partes
    Division division = percent(3333, 3333, 3334);

    assertThat(split("1000.00", division)).containsExactly("333.30", "333.30", "333.40");
    assertThat(sum(SalarySplit.amounts(reais("1000.00"), division)))
        .isEqualByComparingTo("1000.00");
  }

  @Test
  void thousandInThreePartsOf3333IsNotADivisionBecauseItSums9999() {
    // Três partes de 33,33% somam 99,99%, não 100%: a validação da requisição recusa com 400
    // (RecurrenceCreationTest). O componente não corrige a soma, só reparte o que recebe.
    assertThat(split("1000.00", percent(3333, 3333, 3333)))
        .containsExactly("333.30", "333.30", "333.40");
  }

  @Test
  void centInTwoHalvesGivesZeroAndOneCent() {
    // A soma fecha, mas a primeira parcela vale 0,00: a criação recusa com 400.
    assertThat(split("0.01", percent(5000, 5000))).containsExactly("0.00", "0.01");
  }

  @Test
  void fiveThousandIn40And60() {
    assertThat(split("5000.00", percent(4000, 6000))).containsExactly("2000.00", "3000.00");
  }

  @Test
  void hundredInTenPartsOf10() {
    int[] tenths = new int[10];
    java.util.Arrays.fill(tenths, 1000);

    List<BigDecimal> amounts = SalarySplit.amounts(reais("100.00"), percent(tenths));

    assertThat(amounts).hasSize(10).allSatisfy(a -> assertThat(a).isEqualByComparingTo("10.00"));
    assertThat(sum(amounts)).isEqualByComparingTo("100.00");
  }

  @Test
  void truncatesInsteadOfRounding() {
    // 33,33% de 0,10 = 0,03333: truncado dá 0,03; arredondado também. 66,67% de 0,10 = 0,06667:
    // arredondar daria 0,07 e a soma passaria de 0,10 se a última não pegasse o resto.
    assertThat(split("0.10", percent(6667, 3333))).containsExactly("0.06", "0.04");
  }

  @Test
  void amountAtTheCeilingStaysExactAndSums() {
    BigDecimal total = new BigDecimal("90071992547409.91");

    List<BigDecimal> amounts = SalarySplit.amounts(total, percent(3333, 3333, 3334));

    assertThat(amounts).allSatisfy(a -> assertThat(a.scale()).isEqualTo(2));
    assertThat(sum(amounts)).isEqualByComparingTo(total);
    assertThat(amounts.get(0)).isEqualByComparingTo("30020995116051.72");
  }

  @Test
  void fixedAmountsAreReturnedAsTheyAre() {
    assertThat(split("50.00", fixed(2000, 3000))).containsExactly("20.00", "30.00");
  }
}
