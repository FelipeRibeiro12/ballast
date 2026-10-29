package com.felipe.ballast.imports;

// PDF que não dá para ler como fatura. A mensagem é fixa e vai para o cliente (422): nunca leva
// trecho do arquivo nem a mensagem da biblioteca.
class PdfException extends RuntimeException {

  PdfException(String message) {
    super(message);
  }
}
