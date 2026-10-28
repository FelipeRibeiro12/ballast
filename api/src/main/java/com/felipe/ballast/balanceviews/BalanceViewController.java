package com.felipe.ballast.balanceviews;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.CanonicalUuid;
import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho. Cada visão é
// um cartão do carrossel de saldo no Início (ADR-068).
@Tag(name = "visoes-saldo")
@RestController
class BalanceViewController {

  private static final Logger log = LoggerFactory.getLogger(BalanceViewController.class);

  private static final ErrorResponse NOT_FOUND = new ErrorResponse("visão não encontrada");
  private static final ErrorResponse LAST_VIEW =
      new ErrorResponse("a última visão não pode ser apagada");

  private final BalanceViewService service;

  BalanceViewController(BalanceViewService service) {
    this.service = service;
  }

  @Operation(
      operationId = "getVisoesSaldo",
      summary = "Lista as visões de saldo do usuário",
      description =
          "Na primeira leitura cria a visão padrão, Total sem cartão, com todos os tipos menos"
              + " cartão de crédito (ADR-068).",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Visões, da mais antiga para a mais nova",
      content =
          @Content(
              array = @ArraySchema(schema = @Schema(implementation = BalanceViewResponse.class))))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/visoes-saldo", produces = MediaType.APPLICATION_JSON_VALUE)
  List<BalanceViewResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
    return service.list(user.id()).stream().map(BalanceViewResponse::from).toList();
  }

  @Operation(
      operationId = "postVisoesSaldo",
      summary = "Cria uma visão de saldo",
      description = "Até 10 por usuário.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Visão criada")
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Limite de visões atingido",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/visoes-saldo",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Void> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody BalanceViewRequest request) {
    service.create(user.id(), request.title(), request.types());
    log.info("visão de saldo criada usuario_id={}", user.id());
    return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }

  @Operation(
      operationId = "putVisoesSaldoById",
      summary = "Troca o título e os tipos de conta da visão",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Visão atualizada")
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Visão de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/visoes-saldo/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody BalanceViewRequest request) {
    Optional<UUID> viewId = CanonicalUuid.parse(id);
    if (viewId.isEmpty()
        || !service.update(user.id(), viewId.get(), request.title(), request.types())) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return ResponseEntity.noContent().build();
  }

  @Operation(
      operationId = "deleteVisoesSaldoById",
      summary = "Apaga a visão de saldo",
      description = "A última visão não pode ser apagada.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Visão apagada")
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Visão de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "É a última visão do usuário",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/visoes-saldo/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> viewId = CanonicalUuid.parse(id);
    if (viewId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    Optional<BalanceViewService.Failure> failure = service.delete(user.id(), viewId.get());
    if (failure.isEmpty()) {
      return ResponseEntity.noContent().build();
    }
    return switch (failure.get()) {
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case LAST_VIEW -> ResponseEntity.status(HttpStatus.CONFLICT).body(LAST_VIEW);
    };
  }
}
