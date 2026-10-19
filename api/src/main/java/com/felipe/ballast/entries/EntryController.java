package com.felipe.ballast.entries;

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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "lancamentos")
@RestController
class EntryController {

  private static final Logger log = LoggerFactory.getLogger(EntryController.class);

  // Mesmo corpo para lançamento de outro usuário, inexistente e id que não é UUID: diferenciar
  // diria ao cliente quais ids existem.
  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");
  private static final ErrorResponse ACCOUNT_NOT_FOUND = new ErrorResponse("conta não encontrada");
  private static final ErrorResponse ACCOUNT_ARCHIVED = new ErrorResponse("conta arquivada");
  private static final ErrorResponse CATEGORY_NOT_FOUND =
      new ErrorResponse("categoria não encontrada");

  private static final ErrorResponse INVALID_FROM =
      new ErrorResponse("de deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199");
  private static final ErrorResponse INVALID_TO =
      new ErrorResponse("ate deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199");
  private static final ErrorResponse FROM_AFTER_TO =
      new ErrorResponse("de deve ser anterior ou igual a ate");
  private static final ErrorResponse RANGE_TOO_LONG =
      new ErrorResponse("o intervalo entre de e ate deve ter no máximo 366 dias");
  private static final ErrorResponse INVALID_ACCOUNT_FILTER =
      new ErrorResponse("contaId deve ser um UUID");

  // Conta os dois extremos: 2028-01-01 a 2028-12-31 são 366 dias.
  private static final long MAX_DAYS = 366;

  private final EntryService entries;

  EntryController(EntryService entries) {
    this.entries = entries;
  }

  @Operation(
      operationId = "postLancamentos",
      summary = "Cria um lançamento realizado",
      description =
          "A data enviada vai para dataCompetencia e dataCaixa. Conta e categoria precisam ser do"
              + " usuário, e a categoria precisa ser do mesmo tipo do lançamento; os três casos de"
              + " recusa (inexistente, de outro usuário, id inválido) têm a mesma mensagem.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Lançamento criado",
      content = @Content(schema = @Schema(implementation = EntryResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, inclusive campo não declarado; \"conta não encontrada\","
              + " \"conta arquivada\" ou \"categoria não encontrada\"",
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
      path = "/lancamentos",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody EntryRequest request) {
    EntryService.SaveResult result = entries.create(user.id(), toInput(request));
    if (result instanceof EntryService.SaveResult.Saved saved) {
      log.info("lançamento criado usuario_id={} lancamento_id={}", user.id(), saved.entry().id());
      return ResponseEntity.status(HttpStatus.CREATED).body(EntryResponse.from(saved.entry()));
    }
    return rejection((EntryService.SaveResult.Rejected) result);
  }

  @Operation(
      operationId = "getLancamentos",
      summary = "Lista os lançamentos do usuário num período",
      description =
          "Filtra por dataCaixa, com as duas pontas incluídas, do mais novo para o mais antigo"
              + " (depois criadoEm e id, também decrescentes). de e ate são obrigatórios e o"
              + " intervalo tem no máximo 366 dias contando os dois extremos. contaId, se vier,"
              + " precisa ser um UUID; conta que não é do usuário ou não existe devolve lista"
              + " vazia, sem distinguir.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Lançamentos do período",
      content =
          @Content(array = @ArraySchema(schema = @Schema(implementation = EntryResponse.class))))
  @ApiResponse(
      responseCode = "400",
      description =
          "de ou ate ausente ou fora do formato AAAA-MM-DD (ano de 1900 a 2199), de depois de"
              + " ate, intervalo maior que 366 dias ou contaId que não é UUID",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/lancamentos", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(type = "string", format = "date", requiredMode = Schema.RequiredMode.REQUIRED)
          @RequestParam(name = "de", required = false)
          String from,
      @Schema(type = "string", format = "date", requiredMode = Schema.RequiredMode.REQUIRED)
          @RequestParam(name = "ate", required = false)
          String to,
      @Schema(type = "string", format = "uuid") @RequestParam(name = "contaId", required = false)
          String accountId) {
    // Lidos como texto: o conversor padrão de LocalDate aceita formatos que o contrato não prevê.
    Optional<LocalDate> fromDate = IsoDates.parse(from);
    if (fromDate.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_FROM);
    }
    Optional<LocalDate> toDate = IsoDates.parse(to);
    if (toDate.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_TO);
    }
    if (fromDate.get().isAfter(toDate.get())) {
      return ResponseEntity.badRequest().body(FROM_AFTER_TO);
    }
    if (ChronoUnit.DAYS.between(fromDate.get(), toDate.get()) + 1 > MAX_DAYS) {
      return ResponseEntity.badRequest().body(RANGE_TOO_LONG);
    }
    UUID account = null;
    if (accountId != null) {
      Optional<UUID> parsed = CanonicalUuid.parse(accountId);
      if (parsed.isEmpty()) {
        return ResponseEntity.badRequest().body(INVALID_ACCOUNT_FILTER);
      }
      account = parsed.get();
    }
    List<EntryResponse> body =
        entries.list(user.id(), fromDate.get(), toDate.get(), account).stream()
            .map(EntryResponse::from)
            .toList();
    return ResponseEntity.ok(body);
  }

  @Operation(
      operationId = "getLancamentosById",
      summary = "Devolve um lançamento do usuário",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Lançamento",
      content = @Content(schema = @Schema(implementation = EntryResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Lançamento de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/lancamentos/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> find(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    return CanonicalUuid.parse(id)
        .flatMap(entryId -> entries.find(user.id(), entryId))
        .<ResponseEntity<?>>map(found -> ResponseEntity.ok(EntryResponse.from(found)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
  }

  @Operation(
      operationId = "putLancamentosById",
      summary = "Substitui um lançamento",
      description =
          "Mesmo corpo do POST, substitui todos os campos e grava a data nas duas colunas. Mover"
              + " o lançamento para uma conta arquivada é recusado (400 \"conta arquivada\"). Editar"
              + " sem trocar de conta vale mesmo com a conta arquivada, para corrigir lançamento"
              + " antigo.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Lançamento com os valores novos",
      content = @Content(schema = @Schema(implementation = EntryResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, inclusive campo não declarado; \"conta não encontrada\","
              + " \"conta arquivada\" (só ao trocar de conta) ou \"categoria não encontrada\"",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Lançamento de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
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
  @PutMapping(
      path = "/lancamentos/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody EntryRequest request) {
    Optional<UUID> entryId = CanonicalUuid.parse(id);
    if (entryId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    EntryService.SaveResult result = entries.update(user.id(), entryId.get(), toInput(request));
    if (result instanceof EntryService.SaveResult.Saved saved) {
      log.info(
          "lançamento atualizado usuario_id={} lancamento_id={}", user.id(), saved.entry().id());
      return ResponseEntity.ok(EntryResponse.from(saved.entry()));
    }
    return rejection((EntryService.SaveResult.Rejected) result);
  }

  @Operation(
      operationId = "deleteLancamentosById",
      summary = "Apaga um lançamento",
      description = "Apaga a linha, também numa conta arquivada. O saldo deixa de somá-lo.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Lançamento apagado")
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Lançamento de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/lancamentos/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> entryId = CanonicalUuid.parse(id);
    if (entryId.isEmpty() || !entries.delete(user.id(), entryId.get())) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    log.info("lançamento apagado usuario_id={} lancamento_id={}", user.id(), entryId.get());
    return ResponseEntity.noContent().build();
  }

  private static EntryInput toInput(EntryRequest request) {
    return new EntryInput(
        request.type(),
        request.title(),
        request.description(),
        request.amount(),
        CanonicalUuid.parse(request.categoryId()).orElse(null),
        CanonicalUuid.parse(request.accountId()).orElse(null),
        request.parsedDate());
  }

  private static ResponseEntity<?> rejection(EntryService.SaveResult.Rejected rejected) {
    return switch (rejected) {
      case ENTRY_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case ACCOUNT_NOT_FOUND -> ResponseEntity.badRequest().body(ACCOUNT_NOT_FOUND);
      case ACCOUNT_ARCHIVED -> ResponseEntity.badRequest().body(ACCOUNT_ARCHIVED);
      case CATEGORY_NOT_FOUND -> ResponseEntity.badRequest().body(CATEGORY_NOT_FOUND);
    };
  }
}
