package com.felipe.ballast.imports;

// Arquivo que não dá para ler como CSV. A mensagem é fixa e vai para o cliente (422): nunca leva
// trecho do arquivo.
class CsvException extends RuntimeException {

  CsvException(String message) {
    super(message);
  }
}
