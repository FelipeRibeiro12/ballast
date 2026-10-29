package com.felipe.ballast.wallets;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser). Nenhuma rota recebe endereço no caminho e
// nenhum log daqui o escreve (ADR-071).
@Tag(name = "carteiras")
@RestController
class WalletController {

  private static final Logger log = LoggerFactory.getLogger(WalletController.class);

  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");
  private static final ErrorResponse ACCOUNT_NOT_FOUND = new ErrorResponse("conta não encontrada");
  private static final ErrorResponse NOT_CRYPTO = new ErrorResponse("conta deve ser cripto");
  private static final ErrorResponse ACCOUNT_ARCHIVED = new ErrorResponse("conta arquivada");
  private static final ErrorResponse INVALID_ADDRESS =
      new ErrorResponse("endereço inválido para a rede");
  private static final ErrorResponse DUPLICATE = new ErrorResponse("carteira já cadastrada");

  private final WalletService wallets;

  WalletController(WalletService wallets) {
    this.wallets = wallets;
  }

  @Operation(
      operationId = "postCarteiras",
      summary = "Cadastra um endereço público numa conta cripto",
      description =
          "Só observa: o app nunca pede seed, chave privada nem assinatura de transação. Não lê a"
              + " rede; chame POST /carteiras/{id}/sincronizar.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Carteira cadastrada, sem saldo até a primeira leitura",
      content = @Content(schema = @Schema(implementation = WalletResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, endereço que não serve para a rede (tamanho, checksum EIP-55 ou"
              + " base58), conta inexistente, alheia, de outro tipo ou arquivada",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Endereço já cadastrado na rede, ou teto de carteiras do usuário atingido",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível ou conta travada por mais de 2 s",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/carteiras",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody WalletRequest request) {
    WalletService.CreateResult result =
        wallets.create(
            user.id(),
            CanonicalUuid.parse(request.accountId()).orElse(null),
            request.parsedNetwork(),
            request.address(),
            request.label());
    return switch (result) {
      case WalletService.CreateResult.Created created -> {
        log.info(
            "carteira cadastrada usuario_id={} carteira_id={}",
            user.id(),
            created.view().wallet().id());
        yield ResponseEntity.status(HttpStatus.CREATED).body(WalletResponse.from(created.view()));
      }
      case WalletService.CreateResult.Rejected rejected -> {
        log.warn("carteira recusada: motivo={} usuario_id={}", rejected, user.id());
        yield switch (rejected) {
          case ACCOUNT_NOT_FOUND -> ResponseEntity.badRequest().body(ACCOUNT_NOT_FOUND);
          case NOT_CRYPTO -> ResponseEntity.badRequest().body(NOT_CRYPTO);
          case ACCOUNT_ARCHIVED -> ResponseEntity.badRequest().body(ACCOUNT_ARCHIVED);
          case INVALID_ADDRESS -> ResponseEntity.badRequest().body(INVALID_ADDRESS);
          case DUPLICATE -> ResponseEntity.status(HttpStatus.CONFLICT).body(DUPLICATE);
        };
      }
    };
  }

  @Operation(
      operationId = "getCarteiras",
      summary = "Lista as carteiras observadas do usuário",
      description = "Com o saldo da última leitura que deu certo e a data dela.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Carteiras",
      content =
          @Content(array = @ArraySchema(schema = @Schema(implementation = WalletResponse.class))))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/carteiras", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> list(@AuthenticationPrincipal AuthenticatedUser user) {
    return ResponseEntity.ok(wallets.list(user.id()).stream().map(WalletResponse::from).toList());
  }

  @Operation(
      operationId = "getCarteirasById",
      summary = "Detalha uma carteira",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Carteira",
      content = @Content(schema = @Schema(implementation = WalletResponse.class)))
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
  @GetMapping(path = "/carteiras/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> get(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> walletId = CanonicalUuid.parse(id);
    if (walletId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return wallets
        .find(user.id(), walletId.get())
        .<ResponseEntity<?>>map(view -> ResponseEntity.ok(WalletResponse.from(view)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
  }

  @Operation(
      operationId = "deleteCarteirasById",
      summary = "Apaga a carteira e os movimentos sincronizados dela",
      description = "Os movimentos digitados na conta ficam.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Apagada")
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
      description = "Banco indisponível ou conta travada por mais de 2 s",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/carteiras/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> walletId = CanonicalUuid.parse(id);
    if (walletId.isEmpty() || !wallets.delete(user.id(), walletId.get())) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    log.info("carteira apagada usuario_id={} carteira_id={}", user.id(), walletId.get());
    return ResponseEntity.noContent().build();
  }

  @Operation(
      operationId = "postCarteirasByIdSincronizar",
      summary = "Lê o saldo da carteira na rede",
      description =
          "No máximo uma leitura por carteira a cada 60 s: dentro do prazo devolve o estado atual"
              + " sem chamar o indexador. Indexador fora do ar, sem chave ou com resposta fora do"
              + " formato não é erro: responde 200 com leituraFalhou true e os saldos da leitura"
              + " anterior. A leitura é tudo ou nada.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Carteira depois da leitura (ou como estava, se a leitura falhou)",
      content = @Content(schema = @Schema(implementation = WalletResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Carteira de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Conta arquivada, ou teto de movimentos de cripto atingido",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível ou conta travada por mais de 2 s",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(path = "/carteiras/{id}/sincronizar", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> sync(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> walletId = CanonicalUuid.parse(id);
    if (walletId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return switch (wallets.sync(user.id(), walletId.get())) {
      case WalletService.SyncResult.Done done ->
          ResponseEntity.ok(WalletResponse.from(done.view()));
      case WalletService.SyncResult.Rejected rejected ->
          switch (rejected) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
            case ACCOUNT_ARCHIVED ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(ACCOUNT_ARCHIVED);
          };
    };
  }
}
