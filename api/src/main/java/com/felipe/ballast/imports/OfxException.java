package com.felipe.ballast.imports;

// Arquivo que não dá para ler como extrato. A mensagem é fixa e vai para o cliente (422): nunca
// leva trecho do arquivo.
class OfxException extends RuntimeException {

  OfxException(String message) {
    super(message);
  }
}
