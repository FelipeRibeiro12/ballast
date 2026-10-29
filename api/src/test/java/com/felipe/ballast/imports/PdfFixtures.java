package com.felipe.ballast.imports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

// PDFs de teste gerados na hora com o próprio PDFBox: nenhum arquivo de banco real no repositório.
final class PdfFixtures {

  private PdfFixtures() {}

  // Uma linha de texto por entrada, 40 por página.
  static byte[] statement(List<String> lines) throws IOException {
    return build(lines, null, 0);
  }

  static byte[] withPages(int pages) throws IOException {
    return build(List.of("01/10 COMPRA 10,00"), null, pages);
  }

  static byte[] blank() throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      document.addPage(new PDPage(PDRectangle.A4));
      document.save(out);
      return out.toByteArray();
    }
  }

  static byte[] encrypted(List<String> lines, String userPassword) throws IOException {
    return build(lines, userPassword, 0);
  }

  private static byte[] build(List<String> lines, String userPassword, int pagesOverride)
      throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      int pages = pagesOverride > 0 ? pagesOverride : Math.max(1, (lines.size() + 39) / 40);
      for (int p = 0; p < pages; p++) {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
          content.beginText();
          content.setFont(font, 10);
          content.setLeading(14);
          content.newLineAtOffset(40, 800);
          for (int i = p * 40; i < Math.min(lines.size(), (p + 1) * 40); i++) {
            content.showText(lines.get(i));
            content.newLine();
          }
          if (pagesOverride > 0) {
            content.showText("pagina " + p);
          }
          content.endText();
        }
      }
      if (userPassword != null) {
        StandardProtectionPolicy policy =
            new StandardProtectionPolicy(
                "dono-" + userPassword, userPassword, new AccessPermission());
        policy.setEncryptionKeyLength(128);
        document.protect(policy);
      }
      document.save(out);
      return out.toByteArray();
    }
  }
}
