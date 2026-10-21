package com.felipe.ballast.entries;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "transferencias")
@RestController
class TransferController {

  private static final Logger log = LoggerFactory.getLogger(TransferController.class);

  // Mesmo corpo para transferência de outro usuário, inexistente e id que não é UUID.
  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");
  private static final ErrorResponse ACCOUNT_NOT_FOUND = new ErrorResponse("conta não encontrada");
  private static final ErrorResponse ACCOUNT_ARCHIVED = new ErrorResponse("conta arquivada");
  private static final ErrorResponse SAME_ACCOUNT =
      new ErrorResponse("contas devem ser diferentes");
  private static final ErrorResponse CARD_AS_ORIGIN =
      new ErrorResponse("cartão não pode ser origem de transferência");
  private static final ErrorResponse BENEFIT_ACCOUNT =
      new ErrorResponse("conta de benefício não participa de transferência");
  private static final ErrorResponse BALANCE_OUT_OF_RANGE =
      new ErrorResponse("saldo da conta fora do limite");

  private final TransferService transfers;

  TransferController(TransferService transfers) {
    this.transfers = transfers;
  }

  @Operation(
      operationId = "postTransferencias",
      summary = "Move dinheiro entre duas contas do usuário",
      description =
          "Cria as duas pernas (tipo transferencia, sem categoria, mesmo valor, título, descrição"
              + " e data nas duas datas) e a ligação numa transação. Origem e destino inexistentes,"
              + " de outro usuário ou com id inválido têm a mesma mensagem.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Transferência criada",
      content = @Content(schema = @Schema(implementation = TransferResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, inclusive campo não declarado; \"conta não encontrada\","
              + " \"conta arquivada\" (origem ou destino), \"contas devem ser diferentes\","
              + " \"cartão não pode ser origem de transferência\" ou \"saldo da conta fora do"
              + " limite\" (em qualquer das duas contas)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "Corpo maior que 4 KiB",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "415",
      description = "Content-Type diferente de application/json",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/transferencias",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody TransferRequest request) {
    TransferInput input =
        new TransferInput(
            CanonicalUuid.parse(request.fromAccountId()).orElse(null),
            CanonicalUuid.parse(request.toAccountId()).orElse(null),
            request.amount(),
            request.parsedDate(),
            request.title(),
            request.description());
    TransferService.CreateResult result = transfers.create(user.id(), input);
    if (result instanceof TransferService.CreateResult.Created created) {
      log.info(
          "transferência criada usuario_id={} transferencia_id={}",
          user.id(),
          created.transfer().id());
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(TransferResponse.from(created.transfer()));
    }
    return switch ((TransferService.CreateResult.Rejected) result) {
      case ACCOUNT_NOT_FOUND -> ResponseEntity.badRequest().body(ACCOUNT_NOT_FOUND);
      case ACCOUNT_ARCHIVED -> ResponseEntity.badRequest().body(ACCOUNT_ARCHIVED);
      case SAME_ACCOUNT -> ResponseEntity.badRequest().body(SAME_ACCOUNT);
      case CARD_AS_ORIGIN -> ResponseEntity.badRequest().body(CARD_AS_ORIGIN);
      case BENEFIT_ACCOUNT -> ResponseEntity.badRequest().body(BENEFIT_ACCOUNT);
      case BALANCE_OUT_OF_RANGE -> ResponseEntity.badRequest().body(BALANCE_OUT_OF_RANGE);
    };
  }

  @Operation(
      operationId = "getTransferenciasById",
      summary = "Devolve uma transferência do usuário",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Transferência",
      content = @Content(schema = @Schema(implementation = TransferResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Transferência de outro usuário, inexistente ou id que não é UUID; todos com o mesmo"
              + " corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/transferencias/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> find(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    return CanonicalUuid.parse(id)
        .flatMap(transferId -> transfers.find(user.id(), transferId))
        .<ResponseEntity<?>>map(found -> ResponseEntity.ok(TransferResponse.from(found)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
  }

  @Operation(
      operationId = "deleteTransferenciasById",
      summary = "Apaga uma transferência e as duas pernas",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Apagada")
  @ApiResponse(
      responseCode = "400",
      description =
          "\"saldo da conta fora do limite\": apagar tiraria o saldo de uma das contas da faixa"
              + " de ±9007199254740991 centavos, e nada é apagado",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Transferência de outro usuário, inexistente ou id que não é UUID; todos com o mesmo"
              + " corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/transferencias/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    java.util.Optional<UUID> transferId = CanonicalUuid.parse(id);
    if (transferId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return switch (transfers.delete(user.id(), transferId.get())) {
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case BALANCE_OUT_OF_RANGE -> ResponseEntity.badRequest().body(BALANCE_OUT_OF_RANGE);
      // Resultado do DELETE de lançamento; a transferência nunca o devolve.
      case TRANSFER_LEG, CANCEL_OCCURRENCE, FIRST_INSTALLMENT ->
          throw new IllegalStateException("resultado impossível");
      case DELETED -> {
        log.info(
            "transferência apagada usuario_id={} transferencia_id={}", user.id(), transferId.get());
        yield ResponseEntity.noContent().build();
      }
    };
  }
}
