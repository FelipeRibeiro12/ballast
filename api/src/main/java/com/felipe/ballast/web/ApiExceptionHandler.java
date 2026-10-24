package com.felipe.ballast.web;

import com.felipe.ballast.identity.MfaError;
import com.felipe.ballast.identity.PasswordHasherBusyException;
import java.util.Comparator;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// Toda resposta de erro sai como {"erro": "..."} com texto fixo. O detalhe (exceção, query,
// caminho) fica só no log do servidor.
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    // A mensagem vem da anotação (texto fixo, sem o valor enviado). Ordenar pelo campo deixa
    // a resposta estável quando mais de um campo falha.
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .sorted(Comparator.comparing(FieldError::getField))
            .map(FieldError::getDefaultMessage)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse("requisição inválida");
    return body(status, headers, message);
  }

  // O estouro do limite de corpo chega embrulhado pelo parser de JSON.
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof RequestBodyTooLargeException) {
        return body(HttpStatus.CONTENT_TOO_LARGE, headers, "corpo grande demais");
      }
    }
    return super.handleHttpMessageNotReadable(ex, headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    return body(statusCode, headers, messageFor(statusCode));
  }

  // Recusa do MFA, nas rotas de /auth e em quem pede o código para uma ação sensível. O texto é
  // fixo e o atraso vai em Retry-After.
  @ExceptionHandler(MfaError.class)
  ResponseEntity<ErrorResponse> handleMfa(MfaError error) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(error.status());
    if (error.retryAfter() != null) {
      // Para cima: dizer 0 faria o cliente tentar de novo ainda dentro do atraso.
      long seconds = Math.max(1, (error.retryAfter().toMillis() + 999) / 1000);
      response.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
    }
    return response
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ErrorResponse(error.getMessage()));
  }

  // Banco fora: CannotGetJdbcConnectionException (JdbcClient) é filha da primeira; o JPA falha
  // ao abrir a transação, com a segunda. Conexão que cai no meio da consulta também é a primeira.
  @ExceptionHandler({
    DataAccessResourceFailureException.class,
    CannotCreateTransactionException.class
  })
  ResponseEntity<Object> handleDatabaseUnavailable(Exception ex) {
    log.error("banco indisponível", ex);
    return body(HttpStatus.SERVICE_UNAVAILABLE, new HttpHeaders(), "serviço indisponível");
  }

  // Sem stack trace: num flood seria uma por requisição recusada. Os 5 s são a espera do semáforo.
  @ExceptionHandler(PasswordHasherBusyException.class)
  ResponseEntity<Object> handlePasswordHasherBusy(PasswordHasherBusyException ex) {
    log.warn("argon2 sem vaga livre, requisição recusada");
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "5");
    return body(HttpStatus.SERVICE_UNAVAILABLE, headers, "serviço indisponível");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> handleUnexpected(Exception ex) {
    // A trava ocupada por mais de 2 s não é falha da requisição: o cliente pode tentar de novo.
    if (LockTimeouts.isLockTimeout(ex)) {
      log.warn("espera por trava estourou", ex);
      return body(HttpStatus.SERVICE_UNAVAILABLE, new HttpHeaders(), "serviço indisponível");
    }
    if (LockTimeouts.isDeadlock(ex)) {
      log.warn("transação desfeita por deadlock", ex);
      return body(HttpStatus.SERVICE_UNAVAILABLE, new HttpHeaders(), "serviço indisponível");
    }
    UnexpectedErrorLog.error(log, "erro não tratado", ex);
    return body(HttpStatus.INTERNAL_SERVER_ERROR, new HttpHeaders(), "erro interno");
  }

  private static ResponseEntity<Object> body(
      HttpStatusCode status, HttpHeaders headers, String message) {
    HttpHeaders out = new HttpHeaders();
    out.putAll(headers);
    out.setContentType(MediaType.APPLICATION_JSON);
    return ResponseEntity.status(status).headers(out).body(new ErrorResponse(message));
  }

  static String messageFor(HttpStatusCode status) {
    return switch (status.value()) {
      case 404 -> "não encontrado";
      case 405 -> "método não permitido";
      case 406 -> "formato não aceito";
      case 415 -> "content-type deve ser application/json";
      case 503 -> "serviço indisponível";
      default -> status.is5xxServerError() ? "erro interno" : "requisição inválida";
    };
  }
}
