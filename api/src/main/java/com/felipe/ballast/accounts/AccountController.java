package com.felipe.ballast.accounts;

import com.felipe.ballast.identity.AuthenticatedUser;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "contas")
@RestController
class AccountController {

  private static final Logger log = LoggerFactory.getLogger(AccountController.class);

  // Mesmo corpo para conta de outro usuário, conta inexistente e id que não é UUID: diferenciar
  // diria ao cliente quais ids existem.
  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");

  private static final ErrorResponse NAME_TAKEN =
      new ErrorResponse("já existe uma conta com esse nome");

  private static final ErrorResponse INVALID_ARCHIVED_FILTER =
      new ErrorResponse("arquivadas deve ser true ou false");

  private final AccountService accounts;

  AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  @Operation(
      operationId = "postContas",
      summary = "Cria uma conta",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Conta criada",
      content = @Content(schema = @Schema(implementation = AccountResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida, inclusive campo não declarado",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Já existe uma conta do usuário com esse nome, sem diferenciar caixa",
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
      path = "/contas",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateAccountRequest request) {
    Optional<AccountSummary> created =
        accounts.create(user.id(), request.name(), request.type(), request.initialBalance());
    if (created.isEmpty()) {
      // Sem o nome no log: é dado do usuário e o motivo da recusa já está na mensagem.
      log.warn("conta recusada: nome repetido usuario_id={}", user.id());
      return ResponseEntity.status(HttpStatus.CONFLICT).body(NAME_TAKEN);
    }
    log.info("conta criada usuario_id={} conta_id={}", user.id(), created.get().id());
    return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(created.get()));
  }

  @Operation(
      operationId = "getContas",
      summary = "Lista as contas do usuário",
      description =
          "Ordenada por nome sem diferenciar caixa. Sem o parâmetro, devolve só as ativas.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Contas ativas, ou só as arquivadas com arquivadas=true",
      content =
          @Content(array = @ArraySchema(schema = @Schema(implementation = AccountResponse.class))))
  @ApiResponse(
      responseCode = "400",
      description = "arquivadas com valor diferente de true e false",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/contas", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(
              allowableValues = {"true", "false"},
              defaultValue = "false")
          @RequestParam(name = "arquivadas", required = false)
          String archived) {
    // Lido como texto: o conversor padrão de boolean aceita também 1, yes e on.
    boolean onlyArchived;
    if (archived == null || archived.equals("false")) {
      onlyArchived = false;
    } else if (archived.equals("true")) {
      onlyArchived = true;
    } else {
      return ResponseEntity.badRequest().body(INVALID_ARCHIVED_FILTER);
    }
    List<AccountResponse> body =
        accounts.list(user.id(), onlyArchived).stream().map(AccountResponse::from).toList();
    return ResponseEntity.ok(body);
  }

  @Operation(
      operationId = "getContasById",
      summary = "Devolve uma conta do usuário",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Conta",
      content = @Content(schema = @Schema(implementation = AccountResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Conta de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/contas/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> find(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    return parseId(id)
        .flatMap(accountId -> accounts.find(user.id(), accountId))
        .<ResponseEntity<?>>map(found -> ResponseEntity.ok(AccountResponse.from(found)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
  }

  @Operation(
      operationId = "patchContasById",
      summary = "Arquiva ou desarquiva uma conta",
      description = "Idempotente: repetir o mesmo valor devolve 200 e a mesma conta.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Conta com o estado novo",
      content = @Content(schema = @Schema(implementation = AccountResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida, inclusive campo não declarado",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Conta de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
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
  @PatchMapping(
      path = "/contas/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody UpdateAccountRequest request) {
    Optional<AccountSummary> updated =
        parseId(id)
            .flatMap(accountId -> accounts.setArchived(user.id(), accountId, request.archived()));
    if (updated.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    log.info(
        "conta atualizada usuario_id={} conta_id={} arquivada={}",
        user.id(),
        updated.get().id(),
        updated.get().archived());
    return ResponseEntity.ok(AccountResponse.from(updated.get()));
  }

  // Id malformado é 404 e não 400, para não diferenciar do id que simplesmente não existe.
  private static Optional<UUID> parseId(String id) {
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
