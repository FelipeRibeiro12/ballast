package com.felipe.ballast.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {

  private static final Logger log = LoggerFactory.getLogger(HealthController.class);

  private final HealthRepository repository;

  HealthController(HealthRepository repository) {
    this.repository = repository;
  }

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
