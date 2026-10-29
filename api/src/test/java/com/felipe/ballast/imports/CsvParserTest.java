package com.felipe.ballast.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.felipe.ballast.imports.CsvMapping.DateFormat;
import com.felipe.ballast.imports.CsvMapping.Decimal;
import com.felipe.ballast.imports.CsvMapping.Separator;
import com.felipe.ballast.imports.CsvMapping.Sign;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CsvParserTest {

  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-7000-8000-000000000001");

  // data;valor;descrição, com cabeçalho, no formato dos bancos brasileiros.
  private static final CsvMapping BR =
      new CsvMapping(
          Separator.SEMICOLON,
          DateFormat.DAY_MONTH_YEAR_SLASH,
          Decimal.COMMA,
          Sign.NEGATIVE_EXPENSE,
          true,
          0,
          1,
          2,
          null);

  private static CsvMapping withoutHeader(CsvMapping base) {
    return new CsvMapping(
        base.separator(),
        base.dateFormat(),
        base.decimal(),
        base.sign(),
        false,
        base.dateColumn(),
        base.amountColumn(),
        base.descriptionColumn(),
        base.idColumn());
  }

  private static CsvMapping withId(CsvMapping base, int idColumn) {
    return new CsvMapping(
        base.separator(),
        base.dateFormat(),
        base.decimal(),
        base.sign(),
        base.hasHeader(),
        base.dateColumn(),
        base.amountColumn(),
        base.descriptionColumn(),
        idColumn);
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static CsvFile parse(String text) {
    return CsvParser.parse(bytes(text), BR);
  }

  @Test
  void readsRowsAndSkipsTheHeader() {
    CsvFile file =
        parse("Data;Valor;Descricao\n03/10/2026;-35,90;PADARIA\n05/10/2026;1.500,00;SALARIO\n");

    List<OfxTransaction> rows = file.transactions();
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).order()).isEqualTo(1);
    assertThat(rows.get(0).date()).isEqualTo(LocalDate.of(2026, 10, 3));
    assertThat(rows.get(0).amount()).isEqualTo(new BigDecimal("-35.90"));
    assertThat(rows.get(0).name()).isEqualTo("PADARIA");
    assertThat(rows.get(1).amount()).isEqualTo(new BigDecimal("1500.00"));
    assertThat(file.firstRowColumns()).isEqualTo(3);
  }

  @Test
  void withoutHeaderTheFirstRowIsData() {
    CsvFile file = CsvParser.parse(bytes("03/10/2026;-35,90;PADARIA"), withoutHeader(BR));

    assertThat(file.transactions()).hasSize(1);
  }

  @Test
  void acceptsCrlfLfAndCrLineBreaksAndBom() {
    String body = "﻿Data;Valor;D\r\n03/10/2026;-1,00;A\r04/10/2026;-2,00;B\n05/10/2026;-3,00;C";

    assertThat(parse(body).transactions()).hasSize(3);
  }

  @Test
  void quotedCellKeepsSeparatorDoubledQuotesAndLineBreak() {
    CsvFile file = parse("D;V;X\n03/10/2026;-35,90;\"LOJA; \"\"CENTRO\"\"\nFILIAL 2\"\n");

    assertThat(file.transactions()).hasSize(1);
    assertThat(file.transactions().get(0).name()).isEqualTo("LOJA; \"CENTRO\" FILIAL 2");
  }

  @Test
  void decodesWindows1252WhenTheBytesAreNotUtf8() {
    byte[] content =
        "D;V;X\n03/10/2026;-35,90;AÇÚCAR E CAFÉ\n".getBytes(Charset.forName("windows-1252"));

    CsvFile file = CsvParser.parse(content, BR);

    assertThat(file.transactions().get(0).name()).isEqualTo("AÇÚCAR E CAFÉ");
  }

  @Test
  void blankRowsAreIgnoredAndDoNotCountInTheOrder() {
    CsvFile file = parse("D;V;X\n\n03/10/2026;-1,00;A\n;;\n   \n04/10/2026;-2,00;B\n\n");

    assertThat(file.transactions()).extracting(OfxTransaction::order).containsExactly(1, 2);
  }

  @Test
  void shortRowIsInvalidWithoutDroppingTheFile() {
    CsvFile file = parse("D;V;X\n03/10/2026;-1,00\n04/10/2026;-2,00;B\n");

    OfxTransaction first = file.transactions().get(0);
    assertThat(first.invalidReason()).isEqualTo("linha com menos colunas que o mapeamento");
    assertThat(first.date()).isNull();
    assertThat(first.amount()).isNull();
    assertThat(file.transactions().get(1).invalidReason()).isNull();
  }

  @Test
  void impossibleDateAndUnreadableAmountMakeTheRowInvalid() {
    CsvFile file =
        parse(
            "D;V;X\n31/02/2026;-1,00;A\n03/10/2026;abc;B\n03/10/1850;-1,00;C\n03/10/2026;(5,00);D\n");

    assertThat(file.transactions())
        .extracting(OfxTransaction::invalidReason)
        .containsExactly("data inválida", "valor inválido", "data inválida", "valor inválido");
  }

  @Test
  void otherDateFormatsAreStrict() {
    CsvMapping iso =
        new CsvMapping(
            Separator.COMMA,
            DateFormat.YEAR_MONTH_DAY_DASH,
            Decimal.DOT,
            Sign.NEGATIVE_EXPENSE,
            false,
            0,
            1,
            2,
            null);
    CsvMapping us =
        new CsvMapping(
            Separator.TAB,
            DateFormat.MONTH_DAY_YEAR_SLASH,
            Decimal.DOT,
            Sign.NEGATIVE_EXPENSE,
            false,
            0,
            1,
            2,
            null);
    CsvMapping dashes =
        new CsvMapping(
            Separator.PIPE,
            DateFormat.DAY_MONTH_YEAR_DASH,
            Decimal.COMMA,
            Sign.NEGATIVE_EXPENSE,
            false,
            0,
            1,
            2,
            null);

    assertThat(
            CsvParser.parse(bytes("2026-10-03,\"-1,234.56\",A"), iso).transactions().get(0).date())
        .isEqualTo(LocalDate.of(2026, 10, 3));
    assertThat(CsvParser.parse(bytes("10/03/2026\t-1.50\tA"), us).transactions().get(0).date())
        .isEqualTo(LocalDate.of(2026, 10, 3));
    assertThat(CsvParser.parse(bytes("03-10-2026|-1,50|A"), dashes).transactions().get(0).date())
        .isEqualTo(LocalDate.of(2026, 10, 3));
    assertThat(
            CsvParser.parse(bytes("2026-02-30,-1.00,A"), iso).transactions().get(0).invalidReason())
        .isEqualTo("data inválida");
  }

  @Test
  void positiveExpenseFlipsTheSign() {
    CsvMapping card =
        new CsvMapping(
            Separator.SEMICOLON,
            DateFormat.DAY_MONTH_YEAR_SLASH,
            Decimal.COMMA,
            Sign.POSITIVE_EXPENSE,
            false,
            0,
            1,
            2,
            null);

    CsvFile file =
        CsvParser.parse(bytes("03/10/2026;35,90;COMPRA\n04/10/2026;-10,00;ESTORNO"), card);

    assertThat(file.transactions().get(0).amount()).isEqualTo(new BigDecimal("-35.90"));
    assertThat(file.transactions().get(1).amount()).isEqualTo(new BigDecimal("10.00"));
  }

  @Test
  void injectedFormulasGetAnApostropheInTheTitleAndInTheRawCells() {
    String[] cells = {"=HYPERLINK(\"http://x\")", "+1", "-2+3", "@SUM(A1)", "\tcmd", "\rcmd"};
    StringBuilder text = new StringBuilder("D;V;X\n");
    for (String cell : cells) {
      text.append("03/10/2026;-1,00;\"").append(cell.replace("\"", "\"\"")).append("\"\n");
    }

    CsvFile file = parse(text.toString());

    assertThat(file.transactions()).hasSize(6);
    for (OfxTransaction row : file.transactions()) {
      assertThat(row.name()).startsWith("'");
      assertThat(row.raw().get("c3")).startsWith("'");
    }
    assertThat(file.transactions().get(0).name()).isEqualTo("'=HYPERLINK(\"http://x\")");
    // O valor usa a célula crua: -1,00 é número, não fórmula.
    assertThat(file.transactions().get(0).amount()).isEqualTo(new BigDecimal("-1.00"));
    assertThat(file.transactions().get(0).raw().get("c2")).isEqualTo("'-1,00");
  }

  @Test
  void aCellThatIsNotAFormulaIsLeftAlone() {
    CsvFile file = parse("D;V;X\n03/10/2026;-1,00;PADARIA = BOA\n");

    assertThat(file.transactions().get(0).name()).isEqualTo("PADARIA = BOA");
  }

  @Test
  void cellsAreCutAt200Characters() {
    String huge = "A".repeat(5000);

    CsvFile file = parse("D;V;X\n03/10/2026;-1,00;" + huge + "\n");

    OfxTransaction row = file.transactions().get(0);
    assertThat(row.raw().get("c3")).hasSize(200);
    assertThat(row.name()).hasSize(100);
    assertThat(row.memo()).hasSize(200);
  }

  @Test
  void shortDescriptionHasNoMemoAndEmptyOneHasNoName() {
    CsvFile file = parse("D;V;X\n03/10/2026;-1,00;CAFE\n04/10/2026;-2,00;\n");

    assertThat(file.transactions().get(0).memo()).isNull();
    assertThat(file.transactions().get(1).name()).isNull();
    assertThat(ImportPlanner.title(file.transactions().get(1))).isEqualTo("Sem descrição");
  }

  @Test
  void longDescriptionKeepsTheWholeTextAsMemo() {
    String text = "B".repeat(150);

    CsvFile file = parse("D;V;X\n03/10/2026;-1,00;" + text + "\n");

    OfxTransaction row = file.transactions().get(0);
    assertThat(ImportPlanner.title(row)).hasSize(100);
    assertThat(ImportPlanner.description(row)).isEqualTo(text);
  }

  @Test
  void refusesMoreThan2000DataRows() {
    StringBuilder text = new StringBuilder("D;V;X\n");
    for (int i = 0; i < 2001; i++) {
      text.append("03/10/2026;-1,00;A\n");
    }

    assertThatThrownBy(() -> parse(text.toString()))
        .isInstanceOf(CsvException.class)
        .hasMessage("arquivo com mais de 2000 linhas");
  }

  @Test
  void accepts2000DataRows() {
    StringBuilder text = new StringBuilder("D;V;X\n");
    for (int i = 0; i < 2000; i++) {
      text.append("03/10/2026;-1,00;A\n");
    }

    assertThat(parse(text.toString()).transactions()).hasSize(2000);
  }

  @Test
  void refusesMoreThan50Columns() {
    String row = "03/10/2026;-1,00;A" + ";x".repeat(47);

    assertThat(parse("D;V;X\n" + row + "\n").transactions()).hasSize(1);
    assertThatThrownBy(() -> parse("D;V;X\n" + row + ";y\n"))
        .isInstanceOf(CsvException.class)
        .hasMessage("arquivo com mais de 50 colunas por linha");
  }

  @Test
  void refusesNulByte() {
    assertThatThrownBy(() -> parse("D;V;X\n03/10/2026;-1,00;A\0B\n"))
        .isInstanceOf(CsvException.class)
        .hasMessage("o arquivo não parece um CSV de texto");
  }

  @Test
  void refusesEmptyHeaderOnlyAndUnterminatedQuote() {
    assertThatThrownBy(() -> CsvParser.parse(new byte[0], BR))
        .isInstanceOf(CsvException.class)
        .hasMessage("arquivo vazio");
    assertThatThrownBy(() -> parse("D;V;X\n\n"))
        .isInstanceOf(CsvException.class)
        .hasMessage("nenhuma linha de dados encontrada");
    assertThatThrownBy(() -> parse("D;V;X\n03/10/2026;-1,00;\"sem fechar\n04/10/2026;-2,00;B\n"))
        .isInstanceOf(CsvException.class)
        .hasMessage("aspas sem fechar no arquivo");
  }

  @Test
  void errorMessagesNeverEchoTheContent() {
    assertThatThrownBy(() -> parse("D;V;X\n03/10/2026;-1,00;\"SEGREDO123\n"))
        .hasMessageNotContaining("SEGREDO123");
  }

  // Hashes (decisão 8).

  @Test
  void hashWithIdDependsOnAccountAndIdOnly() {
    CsvMapping mapping = withId(BR, 3);
    CsvFile one = CsvParser.parse(bytes("D;V;X;I\n03/10/2026;-1,00;A;ID9\n"), mapping);
    CsvFile other = CsvParser.parse(bytes("D;V;X;I\n09/11/2026;-77,00;Z;ID9\n"), mapping);

    assertThat(one.hashes(ACCOUNT)).isEqualTo(other.hashes(ACCOUNT));
    assertThat(one.hashes(ACCOUNT).get(0)).matches("[0-9a-f]{64}");
    assertThat(one.hashes(UUID.randomUUID())).isNotEqualTo(one.hashes(ACCOUNT));
  }

  @Test
  void repeatedIdGivesTheSameHashSoThePlannerFlagsTheSecondOne() {
    CsvFile file =
        CsvParser.parse(
            bytes("D;V;X;I\n03/10/2026;-1,00;A;ID9\n04/10/2026;-2,00;B;ID9\n"), withId(BR, 3));

    List<String> hashes = file.hashes(ACCOUNT);
    assertThat(hashes.get(0)).isEqualTo(hashes.get(1));
  }

  @Test
  void emptyIdCellFallsBackToTheCompositionHash() {
    CsvMapping mapping = withId(BR, 3);
    CsvFile withId = CsvParser.parse(bytes("D;V;X;I\n03/10/2026;-1,00;A;ID9\n"), mapping);
    CsvFile without = CsvParser.parse(bytes("D;V;X;I\n03/10/2026;-1,00;A;\n"), mapping);

    assertThat(without.hashes(ACCOUNT).get(0)).isNotEqualTo(withId.hashes(ACCOUNT).get(0));
    assertThat(without.hashes(ACCOUNT).get(0))
        .isEqualTo(parse("D;V;X\n03/10/2026;-1,00;A\n").hashes(ACCOUNT).get(0));
  }

  @Test
  void identicalRowsGetDistinctHashesByOrdinalInFileOrder() {
    CsvFile file =
        parse("D;V;X\n03/10/2026;-8,00;CAFE\n03/10/2026;-8,00;CAFE\n03/10/2026;-8,00;CAFE\n");

    List<String> hashes = file.hashes(ACCOUNT);
    assertThat(hashes).doesNotHaveDuplicates();
    // Um extrato que traz só os dois últimos cafés reconhece os dois primeiros.
    CsvFile overlap = parse("D;V;X\n03/10/2026;-8,00;CAFE\n03/10/2026;-8,00;CAFE\n");
    assertThat(overlap.hashes(ACCOUNT)).containsExactly(hashes.get(0), hashes.get(1));
  }

  @Test
  void compositionHashNormalizesTheDescriptionAndIgnoresTheApostrophe() {
    CsvFile one = parse("D;V;X\n03/10/2026;-8,00;  Café   DA  Esquina \n");
    CsvFile two = parse("D;V;X\n03/10/2026;-8,00;café da esquina\n");
    CsvFile formula = parse("D;V;X\n03/10/2026;-8,00;=1+1\n");
    CsvFile formulaPlain = parse("D;V;X\n03/10/2026;-8,00;=1+1\n");

    assertThat(one.hashes(ACCOUNT)).isEqualTo(two.hashes(ACCOUNT));
    assertThat(formula.hashes(ACCOUNT)).isEqualTo(formulaPlain.hashes(ACCOUNT));
    assertThat(formula.descriptionKeys().get(0)).isEqualTo("=1+1");
  }

  @Test
  void differentDateOrAmountChangesTheCompositionHash() {
    String base = parse("D;V;X\n03/10/2026;-8,00;CAFE\n").hashes(ACCOUNT).get(0);

    assertThat(parse("D;V;X\n04/10/2026;-8,00;CAFE\n").hashes(ACCOUNT).get(0)).isNotEqualTo(base);
    assertThat(parse("D;V;X\n03/10/2026;-9,00;CAFE\n").hashes(ACCOUNT).get(0)).isNotEqualTo(base);
    assertThat(parse("D;V;X\n03/10/2026;8,00;CAFE\n").hashes(ACCOUNT).get(0)).isNotEqualTo(base);
  }

  @Test
  void invalidRowWithoutIdHasNoHash() {
    CsvFile file = parse("D;V;X\n31/02/2026;-1,00;A\n");

    assertThat(file.hashes(ACCOUNT)).containsExactly((String) null);
  }

  @Test
  void idAndCompositionTagsNeverCollide() {
    CsvMapping mapping = withId(BR, 3);
    CsvFile forged =
        CsvParser.parse(
            bytes("D;V;X;I\n03/10/2026;-1,00;A;" + ACCOUNT + "|2026-10-03|-1.00|a|1\n"), mapping);
    CsvFile composed = parse("D;V;X\n03/10/2026;-1,00;A\n");

    assertThat(forged.hashes(ACCOUNT).get(0)).isNotEqualTo(composed.hashes(ACCOUNT).get(0));
  }
}
