package com.felipe.ballast.crypto;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.CanonicalUuid;
import com.felipe.ballast.web.ErrorResponse;
import com.felipe.ballast.web.Paging;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.OptionalInt;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "cripto")
@RestController
class MovementController {

  private static final Logger log = LoggerFactory.getLogger(MovementController.class);

  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");
  private static final ErrorResponse ACCOUNT_NOT_FOUND = new ErrorResponse("conta não encontrada");
  private static final ErrorResponse NOT_CRYPTO = new ErrorResponse("conta deve ser cripto");
  private static final ErrorResponse ACCOUNT_ARCHIVED = new ErrorResponse("conta arquivada");
  private static final ErrorResponse ASSET_NOT_FOUND = new ErrorResponse("ativo não encontrado");
  private static final ErrorResponse TOO_MANY_DECIMALS =
      new ErrorResponse("quantidade tem mais casas que o ativo permite");
  private static final ErrorResponse SALE_TOO_BIG = new ErrorResponse("venda maior que a posição");
  private static final ErrorResponse WOULD_GO_NEGATIVE =
      new ErrorResponse("apagar deixaria a posição negativa");
  private static final ErrorResponse TYPE_NOT_TYPED =
      new ErrorResponse("tipo deve ser compra ou venda");
  private static final ErrorResponse SYNCED_MOVEMENT =
      new ErrorResponse("movimento sincronizado não se apaga nem se edita");
  private static final ErrorResponse NOT_SYNCED =
      new ErrorResponse("só movimento sincronizado recebe custo por esta rota");
  private static final ErrorResponse HAS_COST = new ErrorResponse("o custo já foi informado");
  private static final ErrorResponse INVALID_ACCOUNT_FILTER =
      new ErrorResponse("contaId deve ser um UUID");
  private static final ErrorResponse INVALID_ASSET_FILTER =
      new ErrorResponse("ativoId deve ser um UUID");

  private final MovementService movements;

  MovementController(MovementService movements) {
    this.movements = movements;
  }

  @Operation(
      operationId = "postMovimentosCripto",
      summary = "Registra uma compra ou venda de cripto numa conta cripto",
      description =
          "A posição do ativo na conta, dia a dia, nunca fica negativa. A conta fica travada até"
              + " o commit.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Movimento gravado",
      content = @Content(schema = @Schema(implementation = MovementResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, conta inexistente, alheia, de outro tipo ou arquivada, ativo"
              + " inexistente ou alheio, quantidade com mais casas que o ativo ou venda maior"
              + " que a posição",
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
      responseCode = "409",
      description =
          "Teto de movimentos do usuário atingido (limite de movimentos de cripto atingido)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível ou conta travada por mais de 2 s",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/movimentos-cripto",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody MovementRequest request) {
    // Entrada e saída só nascem da leitura de uma carteira (ADR-071).
    if (!request.type().typed()) {
      return ResponseEntity.badRequest().body(TYPE_NOT_TYPED);
    }
    // Id malformado tem a mesma resposta de conta ou ativo inexistente.
    MovementService.Input input =
        new MovementService.Input(
            CanonicalUuid.parse(request.accountId()).orElse(null),
            CanonicalUuid.parse(request.assetId()).orElse(null),
            request.type(),
            request.parsedQuantity(),
            request.parsedDate(),
            request.costUsd());
    MovementService.CreateResult result = movements.create(user.id(), input);
    return switch (result) {
      case MovementService.CreateResult.Created created -> {
        log.info(
            "movimento cripto criado usuario_id={} movimento_id={}",
            user.id(),
            created.movement().id());
        yield ResponseEntity.status(HttpStatus.CREATED)
            .body(MovementResponse.from(created.movement()));
      }
      case MovementService.CreateResult.Rejected rejected -> {
        log.warn("movimento cripto recusado: motivo={} usuario_id={}", rejected, user.id());
        yield switch (rejected) {
          case ACCOUNT_NOT_FOUND -> ResponseEntity.badRequest().body(ACCOUNT_NOT_FOUND);
          case NOT_CRYPTO -> ResponseEntity.badRequest().body(NOT_CRYPTO);
          case ACCOUNT_ARCHIVED -> ResponseEntity.badRequest().body(ACCOUNT_ARCHIVED);
          case ASSET_NOT_FOUND -> ResponseEntity.badRequest().body(ASSET_NOT_FOUND);
          case TOO_MANY_DECIMALS -> ResponseEntity.badRequest().body(TOO_MANY_DECIMALS);
          case SALE_TOO_BIG -> ResponseEntity.badRequest().body(SALE_TOO_BIG);
        };
      }
    };
  }

  @Operation(
      operationId = "getMovimentosCripto",
      summary = "Lista os movimentos de cripto do usuário",
      description =
          "Por data decrescente, depois criação e id, o mais novo primeiro. contaId e ativoId de"
              + " outro usuário dão lista vazia. limite (1 a 1000, padrão 1000) corta a página; se"
              + " houver mais, o cabeçalho X-Next-Cursor traz o cursor para o parâmetro cursor da"
              + " próxima chamada, com os mesmos filtros. Sem mais itens o cabeçalho não vem.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Movimentos",
      headers =
          @Header(
              name = Paging.NEXT_CURSOR_HEADER,
              description = "Cursor da próxima página; ausente na última",
              schema = @Schema(type = "string")),
      content =
          @Content(array = @ArraySchema(schema = @Schema(implementation = MovementResponse.class))))
  @ApiResponse(
      responseCode = "400",
      description = "contaId ou ativoId que não é UUID, limite fora de 1 a 1000 ou cursor inválido",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/movimentos-cripto", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(type = "string", format = "uuid") @RequestParam(name = "contaId", required = false)
          String accountId,
      @Schema(type = "string", format = "uuid") @RequestParam(name = "ativoId", required = false)
          String assetId,
      @Schema(type = "integer", minimum = "1", maximum = "1000", defaultValue = "1000")
          @RequestParam(name = "limite", required = false)
          String limit,
      @Schema(type = "string") @RequestParam(name = "cursor", required = false) String cursor) {
    Optional<UUID> account = CanonicalUuid.parse(accountId);
    if (accountId != null && account.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_ACCOUNT_FILTER);
    }
    Optional<UUID> asset = CanonicalUuid.parse(assetId);
    if (assetId != null && asset.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_ASSET_FILTER);
    }
    OptionalInt pageSize = Paging.parseLimit(limit);
    if (pageSize.isEmpty()) {
      return ResponseEntity.badRequest().body(Paging.INVALID_LIMIT);
    }
    Optional<MovementCursor> after =
        cursor == null ? Optional.empty() : MovementCursor.parse(cursor);
    if (cursor != null && after.isEmpty()) {
      return ResponseEntity.badRequest().body(Paging.INVALID_CURSOR);
    }
    MovementService.Page page =
        movements.list(
            user.id(),
            account.orElse(null),
            asset.orElse(null),
            after.orElse(null),
            pageSize.getAsInt());
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (page.more()) {
      response.header(
          Paging.NEXT_CURSOR_HEADER,
          MovementCursor.of(page.items().get(page.items().size() - 1)).encode());
    }
    return response.body(page.items().stream().map(MovementResponse::from).toList());
  }

  @Operation(
      operationId = "deleteMovimentosCriptoById",
      summary = "Apaga um movimento de cripto",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Apagado")
  @ApiResponse(
      responseCode = "400",
      description =
          "\"apagar deixaria a posição negativa\": a compra cobre uma venda, e nada é apagado",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Movimento sincronizado de uma carteira: só some quando a carteira é apagada",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Movimento de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível ou conta travada por mais de 2 s",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/movimentos-cripto/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> movementId = CanonicalUuid.parse(id);
    if (movementId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return switch (movements.delete(user.id(), movementId.get())) {
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case WOULD_GO_NEGATIVE -> ResponseEntity.badRequest().body(WOULD_GO_NEGATIVE);
      case SYNCED -> ResponseEntity.status(HttpStatus.CONFLICT).body(SYNCED_MOVEMENT);
      case DELETED -> {
        log.info(
            "movimento cripto apagado usuario_id={} movimento_id={}", user.id(), movementId.get());
        yield ResponseEntity.noContent().build();
      }
    };
  }

  @Operation(
      operationId = "putMovimentosCriptoByIdCusto",
      summary = "Informa o custo de uma entrada ou saída sincronizada",
      description =
          "Em dólar (ADR-038), uma vez só. Depois disso o movimento conta como compra ou venda no"
              + " custo médio. Movimento digitado não usa esta rota.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Movimento com o custo gravado",
      content = @Content(schema = @Schema(implementation = MovementResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida ou movimento digitado (não sincronizado)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Movimento de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "O custo já foi informado",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível ou conta travada por mais de 2 s",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/movimentos-cripto/{id}/custo",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> setCost(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody MovementCostRequest request) {
    Optional<UUID> movementId = CanonicalUuid.parse(id);
    if (movementId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return switch (movements.setCost(user.id(), movementId.get(), request.costUsd())) {
      case MovementService.CostResult.Saved saved -> {
        log.info(
            "custo de movimento sincronizado informado usuario_id={} movimento_id={}",
            user.id(),
            movementId.get());
        yield ResponseEntity.ok(MovementResponse.from(saved.movement()));
      }
      case MovementService.CostResult.Rejected rejected ->
          switch (rejected) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
            case NOT_SYNCED -> ResponseEntity.badRequest().body(NOT_SYNCED);
            case ALREADY_HAS_COST -> ResponseEntity.status(HttpStatus.CONFLICT).body(HAS_COST);
          };
    };
  }
}
