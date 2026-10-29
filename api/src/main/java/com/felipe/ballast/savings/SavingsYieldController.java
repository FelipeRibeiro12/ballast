package com.felipe.ballast.savings;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.CanonicalUuid;
import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "contas")
@RestController
class SavingsYieldController {

  private static final Logger log = LoggerFactory.getLogger(SavingsYieldController.class);

  // Mesmo corpo para conta de outro usuário, inexistente, que não é cofrinho e id que não é UUID.
  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");

  private final SavingsYieldService yields;

  SavingsYieldController(SavingsYieldService yields) {
    this.yields = yields;
  }

  @Operation(
      operationId = "postContasByIdCofrinhoRendimento",
      summary = "Lança agora o rendimento de hoje do cofrinho",
      description =
          "Roda a conta para hoje, como o job diário, sem esperar as 03:30. Repetir no mesmo dia"
              + " responde ja_lancado e não duplica. Hoje sem dia útil responde sem_rendimento"
              + " (ADR-074). O corpo é vazio.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "O que a rodada fez; valor só vem em lancado",
      content = @Content(schema = @Schema(implementation = SavingsYieldResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Conta de outro usuário, inexistente, que não é cofrinho ou id que não é UUID; mesmo"
              + " corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "429",
      description = "Limite geral de requisições por IP (ADR-049)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Conta em uso por outra requisição; tentar de novo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/contas/{id}/cofrinho/rendimento",
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> launchToday(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> accountId = CanonicalUuid.parse(id);
    if (accountId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    SavingsYieldService.Result result = yields.runNow(user.id(), accountId.get());
    if (result == SavingsYieldService.Result.Skipped.NOT_FOUND) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    if (result instanceof SavingsYieldService.Result.Launched) {
      log.info(
          "rendimento do cofrinho lançado usuario_id={} conta_id={}", user.id(), accountId.get());
    }
    return ResponseEntity.ok(SavingsYieldResponse.from(result));
  }
}
