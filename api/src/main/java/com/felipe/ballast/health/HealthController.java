package com.felipe.ballast.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

  private final HealthRepository repository;

  HealthController(HealthRepository repository) {
    this.repository = repository;
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
    try {
      return ResponseEntity.ok(new HealthResponse(repository.findMessage()));
    } catch (DataAccessException e) {
      // A exceção do driver pode carregar host e usuário do banco: só vai para o log.
      log.error("healthz: falha ao consultar o banco", e);
      return ResponseEntity.status(503).body(new HealthError("indisponível"));
    }
  }
}
