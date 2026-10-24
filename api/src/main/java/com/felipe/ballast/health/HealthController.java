package com.felipe.ballast.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "health")
@RestController
class HealthController {

  private static final Logger log = LoggerFactory.getLogger(HealthController.class);

  private static final Duration LOG_INTERVAL = Duration.ofMinutes(1);

  private final HealthRepository repository;
  private final Clock clock;
  // Uma consulta por vez: o cron e o deploy chamam um de cada vez, e num flood o /healthz fica
  // com no máximo uma conexão do pool. Quem chega com a vaga ocupada leva 503 sem tocar no banco.
  private final Semaphore slot = new Semaphore(1);
  private final AtomicReference<Instant> lastFailureLog = new AtomicReference<>(Instant.MIN);

  HealthController(HealthRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Operation(operationId = "getHealth", summary = "Verifica se a API alcança o banco")
  @ApiResponse(
      responseCode = "200",
      description = "API e banco respondendo",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = HealthResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = HealthError.class)))
  @GetMapping(path = "/healthz", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> check() {
    if (!slot.tryAcquire()) {
      return unavailable();
    }
    try {
      return ResponseEntity.ok(new HealthResponse(repository.findMessage()));
    } catch (DataAccessException e) {
      logAtMostOncePerMinute(e);
      return unavailable();
    } finally {
      slot.release();
    }
  }

  // A exceção do driver pode carregar host e usuário do banco: só vai para o log. Uma por minuto
  // basta para saber que o banco caiu; uma por requisição enche o disco num flood.
  private void logAtMostOncePerMinute(DataAccessException e) {
    Instant now = clock.instant();
    Instant last = lastFailureLog.get();
    if (now.isBefore(last.plus(LOG_INTERVAL)) || !lastFailureLog.compareAndSet(last, now)) {
      return;
    }
    log.error("healthz: falha ao consultar o banco", e);
  }

  private static ResponseEntity<HealthError> unavailable() {
    return ResponseEntity.status(503).body(new HealthError("indisponível"));
  }
}
