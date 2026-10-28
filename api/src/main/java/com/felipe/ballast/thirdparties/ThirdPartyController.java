package com.felipe.ballast.thirdparties;

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

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho. Quem usa o
// cartão do dono e reembolsa depois (ADR-062).
@Tag(name = "terceiros")
@RestController
class ThirdPartyController {

  private static final Logger log = LoggerFactory.getLogger(ThirdPartyController.class);

  private static final ErrorResponse NOT_FOUND = new ErrorResponse("terceiro não encontrado");
  private static final ErrorResponse ENTRY_NOT_FOUND =
      new ErrorResponse("lançamento ou compra não encontrada");
  private static final ErrorResponse DUPLICATE =
      new ErrorResponse("já existe terceiro com esse nome");
  private static final ErrorResponse HAS_ENTRIES =
      new ErrorResponse("terceiro tem lançamentos ligados; arquive em vez de apagar");
  private static final ErrorResponse ARCHIVED =
      new ErrorResponse("terceiro arquivado não recebe lançamentos novos");
  private static final ErrorResponse TRANSFER =
      new ErrorResponse("transferência não se liga a terceiro");

  private final ThirdPartyService service;

  ThirdPartyController(ThirdPartyService service) {
    this.service = service;
  }

  @Operation(
      operationId = "getTerceiros",
      summary = "Lista os terceiros com o que compraram e o que já reembolsaram",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Terceiros do usuário, ativos primeiro",
      content =
          @Content(
              array = @ArraySchema(schema = @Schema(implementation = ThirdPartyResponse.class))))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/terceiros", produces = MediaType.APPLICATION_JSON_VALUE)
  List<ThirdPartyResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
    return service.list(user.id()).stream().map(ThirdPartyResponse::from).toList();
  }

  @Operation(
      operationId = "postTerceiros",
      summary = "Cria um terceiro",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Terceiro criado",
      content = @Content(schema = @Schema(implementation = ThirdPartyResponse.class)))
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
      description = "Nome repetido ou limite de terceiros atingido",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/terceiros",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody ThirdPartyRequest request) {
    ThirdPartyService.Result<ThirdPartyQueries.ThirdParty> result =
        service.create(user.id(), request.name());
    if (result instanceof ThirdPartyService.Result.Ok<ThirdPartyQueries.ThirdParty> ok) {
      log.info("terceiro criado usuario_id={} terceiro_id={}", user.id(), ok.value().id());
      return ResponseEntity.status(HttpStatus.CREATED).body(ThirdPartyResponse.from(ok.value()));
    }
    return failure(result);
  }

  @Operation(
      operationId = "putTerceirosById",
      summary = "Troca o nome e o arquivamento do terceiro",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Terceiro atualizado",
      content = @Content(schema = @Schema(implementation = ThirdPartyResponse.class)))
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
      description = "Terceiro de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Nome repetido",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/terceiros/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody ThirdPartyRequest request) {
    Optional<UUID> thirdPartyId = CanonicalUuid.parse(id);
    if (thirdPartyId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    ThirdPartyService.Result<ThirdPartyQueries.ThirdParty> result =
        service.update(user.id(), thirdPartyId.get(), request.name(), request.archivedOrDefault());
    if (result instanceof ThirdPartyService.Result.Ok<ThirdPartyQueries.ThirdParty> ok) {
      return ResponseEntity.ok(ThirdPartyResponse.from(ok.value()));
    }
    return failure(result);
  }

  @Operation(
      operationId = "deleteTerceirosById",
      summary = "Apaga o terceiro sem lançamentos",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Terceiro apagado")
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Terceiro de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Terceiro com lançamentos ligados",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/terceiros/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> thirdPartyId = CanonicalUuid.parse(id);
    if (thirdPartyId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    ThirdPartyService.Result<Boolean> result = service.delete(user.id(), thirdPartyId.get());
    if (result instanceof ThirdPartyService.Result.Ok<Boolean>) {
      log.info("terceiro apagado usuario_id={} terceiro_id={}", user.id(), thirdPartyId.get());
      return ResponseEntity.noContent().build();
    }
    return failure(result);
  }

  @Operation(
      operationId = "getTerceirosByIdLancamentos",
      summary = "Compras e reembolsos ligados ao terceiro",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Do mais novo ao mais antigo pela data de caixa",
      content =
          @Content(
              array =
                  @ArraySchema(schema = @Schema(implementation = ThirdPartyEntryResponse.class))))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Terceiro de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/terceiros/{id}/lancamentos", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> entries(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> thirdPartyId = CanonicalUuid.parse(id);
    Optional<List<ThirdPartyQueries.TaggedEntry>> found =
        thirdPartyId.flatMap(value -> service.entries(user.id(), value));
    if (found.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return ResponseEntity.ok(found.get().stream().map(ThirdPartyEntryResponse::from).toList());
  }

  @Operation(
      operationId = "putLancamentosByIdTerceiro",
      summary = "Liga o lançamento a um terceiro ou solta o vínculo",
      description =
          "Despesa ligada é compra do terceiro; receita ligada é reembolso. Compra de terceiro"
              + " sai de relatórios, orçamento e do Posso gastar (ADR-062).",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Vínculo gravado")
  @ApiResponse(
      responseCode = "400",
      description = "Transferência ou terceiro arquivado",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Lançamento ou terceiro de outro usuário ou inexistente; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/lancamentos/{id}/terceiro",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> linkEntry(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody LinkThirdPartyRequest request) {
    Optional<UUID> entryId = CanonicalUuid.parse(id);
    if (entryId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ENTRY_NOT_FOUND);
    }
    Optional<UUID> thirdPartyId = parseOptional(request.thirdPartyId());
    if (request.thirdPartyId() != null && thirdPartyId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    ThirdPartyService.Result<Boolean> result =
        service.setEntryThirdParty(user.id(), entryId.get(), thirdPartyId.orElse(null));
    return linked(result, user, entryId.get());
  }

  @Operation(
      operationId = "putComprasParceladasByIdTerceiro",
      summary = "Liga todas as parcelas da compra a um terceiro ou solta o vínculo",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Vínculo gravado")
  @ApiResponse(
      responseCode = "400",
      description = "Terceiro arquivado",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Compra ou terceiro de outro usuário ou inexistente; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/compras-parceladas/{id}/terceiro",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> linkPurchase(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody LinkThirdPartyRequest request) {
    Optional<UUID> purchaseId = CanonicalUuid.parse(id);
    if (purchaseId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ENTRY_NOT_FOUND);
    }
    Optional<UUID> thirdPartyId = parseOptional(request.thirdPartyId());
    if (request.thirdPartyId() != null && thirdPartyId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    ThirdPartyService.Result<Boolean> result =
        service.setPurchaseThirdParty(user.id(), purchaseId.get(), thirdPartyId.orElse(null));
    return linked(result, user, purchaseId.get());
  }

  private ResponseEntity<?> linked(
      ThirdPartyService.Result<Boolean> result, AuthenticatedUser user, UUID targetId) {
    if (result instanceof ThirdPartyService.Result.Ok<Boolean>) {
      log.info("vinculo de terceiro gravado usuario_id={} alvo_id={}", user.id(), targetId);
      return ResponseEntity.noContent().build();
    }
    ThirdPartyService.Failure failure = ((ThirdPartyService.Result.Failed<Boolean>) result).failure();
    // NOT_FOUND aqui pode ser o lançamento ou o terceiro; o corpo é o mesmo para não revelar qual.
    return failure == ThirdPartyService.Failure.NOT_FOUND
        ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(ENTRY_NOT_FOUND)
        : failure(result);
  }

  private static Optional<UUID> parseOptional(String raw) {
    return raw == null ? Optional.empty() : CanonicalUuid.parse(raw);
  }

  private static <T> ResponseEntity<?> failure(ThirdPartyService.Result<T> result) {
    ThirdPartyService.Failure failure = ((ThirdPartyService.Result.Failed<T>) result).failure();
    return switch (failure) {
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case DUPLICATE_NAME -> ResponseEntity.status(HttpStatus.CONFLICT).body(DUPLICATE);
      case HAS_ENTRIES -> ResponseEntity.status(HttpStatus.CONFLICT).body(HAS_ENTRIES);
      case ARCHIVED -> ResponseEntity.badRequest().body(ARCHIVED);
      case TRANSFER_ENTRY -> ResponseEntity.badRequest().body(TRANSFER);
    };
  }
}
