package com.felipe.ballast.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class OfxParserTest {

  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

  private static byte[] sgml(String transactions) {
    return ("OFXHEADER:100\r\nDATA:OFXSGML\r\nVERSION:102\r\nENCODING:USASCII\r\nCHARSET:1252\r\n\r\n"
            + "<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>\r\n"
            + transactions
            + "</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>")
        .getBytes(WINDOWS_1252);
  }

  private static byte[] xml(String transactions) {
    return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<?OFX OFXHEADER=\"200\" VERSION=\"220\"?>\n"
            + "<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>"
            + transactions
            + "</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String sgmlTransaction(String date, String amount, String fitId, String name) {
    return "<STMTTRN>\r\n<TRNTYPE>DEBIT\r\n<DTPOSTED>"
        + date
        + "\r\n<TRNAMT>"
        + amount
        + "\r\n"
        + (fitId == null ? "" : "<FITID>" + fitId + "\r\n")
        + "<MEMO>"
        + name
        + "\r\n</STMTTRN>\r\n";
  }

  @Test
  void readsSgmlVersion1() {
    List<OfxTransaction> found =
        OfxParser.parse(sgml(sgmlTransaction("20261003", "-35.90", "A1", "PADARIA")))
            .transactions();

    assertThat(found).hasSize(1);
    OfxTransaction first = found.get(0);
    assertThat(first.date()).isEqualTo(LocalDate.of(2026, 10, 3));
    assertThat(first.amount()).isEqualByComparingTo(new BigDecimal("-35.90"));
    assertThat(first.fitId()).isEqualTo("A1");
    assertThat(first.memo()).isEqualTo("PADARIA");
    assertThat(first.invalidReason()).isNull();
  }

  @Test
  void readsXmlVersion2() {
    String body =
        "<STMTTRN><TRNTYPE>CREDIT</TRNTYPE><DTPOSTED>20261005</DTPOSTED>"
            + "<TRNAMT>1500.00</TRNAMT><FITID>X9</FITID><NAME>SALARIO</NAME>"
            + "<MEMO>Folha &amp; bonus</MEMO><CHECKNUM>77</CHECKNUM></STMTTRN>";

    OfxTransaction first = OfxParser.parse(xml(body)).transactions().get(0);

    assertThat(first.type()).isEqualTo("CREDIT");
    assertThat(first.amount()).isEqualByComparingTo("1500.00");
    assertThat(first.name()).isEqualTo("SALARIO");
    assertThat(first.memo()).isEqualTo("Folha & bonus");
    assertThat(first.checkNumber()).isEqualTo("77");
  }

  @Test
  void refusesDoctype() {
    byte[] file =
        ("<?xml version=\"1.0\"?><!DOCTYPE OFX [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<OFX><STMTTRN><TRNAMT>1.00</TRNAMT><FITID>&x;</FITID></STMTTRN></OFX>")
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> OfxParser.parse(file)).isInstanceOf(OfxException.class);
  }

  @Test
  void refusesEntityDeclarationEvenInLowerCase() {
    byte[] file =
        "<OFX><!entity x \"y\"><STMTTRN></STMTTRN></OFX>".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> OfxParser.parse(file)).isInstanceOf(OfxException.class);
  }

  @Test
  void acceptsCommaAsDecimalSeparator() {
    OfxTransaction first =
        OfxParser.parse(sgml(sgmlTransaction("20261003", "-35,90", "A1", "X")))
            .transactions()
            .get(0);

    assertThat(first.amount()).isEqualByComparingTo("-35.90");
    assertThat(first.invalidReason()).isNull();
  }

  @Test
  void readsThousandsSeparator() {
    OfxTransaction first =
        OfxParser.parse(sgml(sgmlTransaction("20261003", "1.234,56", "A1", "X")))
            .transactions()
            .get(0);

    assertThat(first.amount()).isEqualByComparingTo("1234.56");
  }

  @Test
  void marksMoreThanTwoDecimalPlacesAsInvalid() {
    OfxTransaction first =
        OfxParser.parse(sgml(sgmlTransaction("20261003", "-10.005", "A1", "X")))
            .transactions()
            .get(0);

    assertThat(first.invalidReason()).isNotNull();
    assertThat(first.amount()).isNull();
  }

  @Test
  void acceptsTrailingZerosBeyondTwoPlaces() {
    OfxTransaction first =
        OfxParser.parse(sgml(sgmlTransaction("20261003", "-10.500", "A1", "X")))
            .transactions()
            .get(0);

    assertThat(first.invalidReason()).isNull();
    assertThat(first.amount()).isEqualByComparingTo("-10.50");
  }

  @Test
  void marksZeroAndMissingAmountAsInvalid() {
    List<OfxTransaction> found =
        OfxParser.parse(
                sgml(
                    sgmlTransaction("20261003", "0.00", "A1", "X")
                        + "<STMTTRN>\r\n<DTPOSTED>20261003\r\n<FITID>A2\r\n</STMTTRN>\r\n"))
            .transactions();

    assertThat(found).allSatisfy(t -> assertThat(t.invalidReason()).isNotNull());
  }

  @Test
  void marksUnreadableDateAsInvalid() {
    OfxTransaction first =
        OfxParser.parse(sgml(sgmlTransaction("2026-13-45", "-1.00", "A1", "X")))
            .transactions()
            .get(0);

    assertThat(first.invalidReason()).isNotNull();
  }

  @Test
  void readsWindows1252Text() {
    OfxTransaction first =
        OfxParser.parse(sgml(sgmlTransaction("20261003", "-5.00", "A1", "AÇAÍ CAFÉ")))
            .transactions()
            .get(0);

    assertThat(first.memo()).isEqualTo("AÇAÍ CAFÉ");
  }

  @Test
  void readsUtf8Text() {
    OfxTransaction first =
        OfxParser.parse(
                xml(
                    "<STMTTRN><DTPOSTED>20261003</DTPOSTED><TRNAMT>-5.00</TRNAMT>"
                        + "<FITID>1</FITID><MEMO>AÇAÍ CAFÉ</MEMO></STMTTRN>"))
            .transactions()
            .get(0);

    assertThat(first.memo()).isEqualTo("AÇAÍ CAFÉ");
  }

  @Test
  void keepsMissingFitIdAsNull() {
    OfxTransaction first =
        OfxParser.parse(sgml(sgmlTransaction("20261003", "-5.00", null, "X")))
            .transactions()
            .get(0);

    assertThat(first.fitId()).isNull();
    assertThat(first.invalidReason()).isNull();
  }

  @Test
  void usesTheWrittenDateWhenThereIsTimeAndZone() {
    OfxTransaction first =
        OfxParser.parse(sgml(sgmlTransaction("20261003000000[-3:BRT]", "-5.00", "A1", "X")))
            .transactions()
            .get(0);

    assertThat(first.date()).isEqualTo(LocalDate.of(2026, 10, 3));
  }

  @Test
  void cutsLongValuesAt200Characters() {
    OfxTransaction first =
        OfxParser.parse(sgml(sgmlTransaction("20261003", "-5.00", "A1", "x".repeat(500))))
            .transactions()
            .get(0);

    assertThat(first.memo()).hasSize(200);
  }

  @Test
  void refusesMoreThan2000Transactions() {
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 2001; i++) {
      many.append(sgmlTransaction("20261003", "-1.00", "F" + i, "X"));
    }

    assertThatThrownBy(() -> OfxParser.parse(sgml(many.toString())))
        .isInstanceOf(OfxException.class)
        .hasMessageContaining("2000");
  }

  @Test
  void accepts2000Transactions() {
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      many.append(sgmlTransaction("20261003", "-1.00", "F" + i, "X"));
    }

    assertThat(OfxParser.parse(sgml(many.toString())).transactions()).hasSize(2000);
  }

  @Test
  void refusesBinaryGarbage() {
    byte[] garbage = new byte[4096];
    new Random(7).nextBytes(garbage);

    assertThatThrownBy(() -> OfxParser.parse(garbage)).isInstanceOf(OfxException.class);
  }

  @Test
  void refusesEmptyFileAndFileWithoutTransactions() {
    assertThatThrownBy(() -> OfxParser.parse(new byte[0])).isInstanceOf(OfxException.class);
    assertThatThrownBy(() -> OfxParser.parse(sgml(""))).isInstanceOf(OfxException.class);
  }

  @Test
  void countsEveryTransactionWhenCloseTagIsMissing() {
    String open =
        "<STMTTRN>\r\n<DTPOSTED>20261003\r\n<TRNAMT>-1.00\r\n<FITID>A\r\n"
            + "<STMTTRN>\r\n<DTPOSTED>20261004\r\n<TRNAMT>-2.00\r\n<FITID>B\r\n";

    assertThat(OfxParser.parse(sgml(open)).transactions()).hasSize(2);
  }

  @Test
  void numbersTransactionsInFileOrder() {
    List<OfxTransaction> found =
        OfxParser.parse(
                sgml(
                    sgmlTransaction("20261003", "-1.00", "A", "X")
                        + sgmlTransaction("20261004", "-2.00", "B", "Y")))
            .transactions();

    assertThat(found).extracting(OfxTransaction::order).containsExactly(1, 2);
  }
}
