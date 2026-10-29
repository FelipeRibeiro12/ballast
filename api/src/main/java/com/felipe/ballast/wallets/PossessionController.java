package com.felipe.ballast.wallets;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.CanonicalUuid;
import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// O dono vem da sessão e a carteira é buscada por usuario_id e id: carteira alheia é 404 igual à
// que não existe. As mensagens de erro não dizem qual parte da prova falhou (ADR-072).
@Tag(name = "carteiras")
@RestController
class PossessionController {

  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");
  private static final ErrorResponse TOO_SOON =
      new ErrorResponse("muitas tentativas, tente mais tarde");
  private static final ErrorResponse EXPIRED = new ErrorResponse("desafio expirado");
  private static final ErrorResponse INVALID_SIGNATURE = new ErrorResponse("assinatura inválida");

  private final PossessionService possession;

  PossessionController(PossessionService possession) {
    this.possession = possession;
  }

  @Operation(
      operationId = "postCarteirasByIdPosseDesafio",
      summary = "Pede a mensagem que o usuário assina para provar a posse da carteira",
      description =
          "O servidor monta o texto (SIWE no EVM, texto equivalente na Solana) e o cliente assina"
              + " exatamente o que recebe. Vale 5 minutos e substitui o desafio anterior. Um"
              + " desafio novo a cada 5 s por carteira. Não assina transação e não custa nada.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Desafio emitido",
      content = @Content(schema = @Schema(implementation = PossessionChallengeResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Carteira de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "429",
      description = "Desafio anterior da carteira tem menos de 5 s; o anterior segue valendo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(path = "/carteiras/{id}/posse/desafio", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> challenge(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> walletId = CanonicalUuid.parse(id);
    if (walletId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return switch (possession.challenge(user.id(), walletId.get())) {
      case PossessionService.ChallengeResult.Ok ok ->
          ResponseEntity.ok(
              new PossessionChallengeResponse(
                  ok.challenge().nonce(), ok.challenge().message(), ok.challenge().expiresAt()));
      case PossessionService.ChallengeResult.No no ->
          switch (no.reason()) {
            case TOO_SOON ->
                ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "5")
                    .body(TOO_SOON);
            default -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
          };
    };
  }

  @Operation(
      operationId = "postCarteirasByIdPosseVerificar",
      summary = "Confere a assinatura do desafio e marca a carteira como verificada",
      description =
          "Consome o desafio antes de olhar a assinatura: errar queima o nonce e o usuário pede"
              + " outro. A falha não diz qual parte errou.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Carteira com verificadaEm preenchido",
      content = @Content(schema = @Schema(implementation = WalletResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, nonce ou assinatura errados (assinatura inválida) ou desafio"
              + " ausente, vencido ou já usado (desafio expirado)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Carteira de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/carteiras/{id}/posse/verificar",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> verify(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody PossessionVerifyRequest request) {
    Optional<UUID> walletId = CanonicalUuid.parse(id);
    if (walletId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return switch (possession.verify(
        user.id(), walletId.get(), request.nonce(), request.signature())) {
      case PossessionService.VerifyResult.Ok ok ->
          ResponseEntity.ok(WalletResponse.from(ok.view()));
      case PossessionService.VerifyResult.No no ->
          switch (no.reason()) {
            case EXPIRED -> ResponseEntity.badRequest().body(EXPIRED);
            case INVALID_SIGNATURE -> ResponseEntity.badRequest().body(INVALID_SIGNATURE);
            default -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
          };
    };
  }
}
