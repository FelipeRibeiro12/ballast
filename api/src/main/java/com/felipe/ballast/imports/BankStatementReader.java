package com.felipe.ballast.imports;

import java.time.LocalDate;

// Leitor de um emissor de cartão. O genérico serve a qualquer fatura; um leitor por banco entra na
// lista de PdfParser antes dele quando o layout daquele banco pedir mais que a heurística.
interface BankStatementReader {

  // O texto extraído parece fatura deste emissor?
  boolean recognizes(String text);

  PdfFile read(String text, LocalDate today);
}
