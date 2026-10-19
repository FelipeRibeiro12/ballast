package com.felipe.ballast.web;

import java.io.IOException;

// IOException para atravessar o parser de JSON como erro de leitura; o ApiExceptionHandler
// procura esta classe na cadeia de causas e responde 413.
class RequestBodyTooLargeException extends IOException {

  RequestBodyTooLargeException(long limit) {
    super("corpo acima de " + limit + " bytes");
  }
}
