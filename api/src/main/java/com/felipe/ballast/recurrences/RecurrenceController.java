package com.felipe.ballast.recurrences;

import com.felipe.ballast.entries.EntryType;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "recorrencias")
@RestController
class RecurrenceController {

  private static final Logger log = LoggerFactory.getLogger(RecurrenceController.class);

  // Mesmo corpo para recorrência de outro usuário, inexistente e id que não é UUID: diferenciar
  // diria ao cliente quais ids existem.
  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");
  private static final ErrorResponse ACCOUNT_NOT_FOUND = new ErrorResponse("conta não encontrada");
  private static final ErrorResponse ACCOUNT_ARCHIVED = new ErrorResponse("conta arquivada");
  private static final ErrorResponse CRYPTO_ACCOUNT =
      new ErrorResponse("conta cripto não recebe lançamento");
  private static final ErrorResponse CATEGORY_NOT_PERMITTED =
      new ErrorResponse("categoria não permitida nesta conta");
  private static final ErrorResponse CATEGORY_NOT_FOUND =
      new ErrorResponse("categoria não encontrada");
  private static final ErrorResponse INVALID_TYPE =
      new ErrorResponse(RecurrenceRequest.TYPE_MESSAGE);
  private static final ErrorResponse TYPE_CHANGED =
      new ErrorResponse("o tipo da recorrência não muda");

  private final RecurrenceService recurrences;

  RecurrenceController(RecurrenceService recurrences) {
    this.recurrences = recurrences;
  }

  @Operation(
      operationId = "postRecorrencias",
      summary = "Cria uma recorrência e materializa as ocorrências dos próximos 12 meses",
      description =
          "Grava a regra, o template e, na mesma transação, as ocorrências previstas de"
              + " max(inicio, hoje) até hoje mais 12 meses, as duas pontas incluídas. A data que"
              + " a regra gera antes de hoje não vira lançamento. Hoje é a data em"
              + " America/Sao_Paulo. Conta e categoria precisam ser do usuário e a categoria do"
              + " mesmo tipo do template. Uma série que já terminou é criada com zero"
              + " ocorrências.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Recorrência criada",
      content = @Content(schema = @Schema(implementation = RecurrenceCreatedResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, inclusive campo não declarado e combinação de campos que a frequência"
              + " não aceita; \"conta não encontrada\", \"conta arquivada\" ou \"categoria não"
              + " encontrada\"",
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
      path = "/recorrencias",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody RecurrenceRequest request) {
    if (request.type() == EntryType.TRANSFER) {
      return ResponseEntity.badRequest().body(INVALID_TYPE);
    }
    Optional<String> problem = request.crossFieldProblem();
    if (problem.isPresent()) {
      return ResponseEntity.badRequest().body(new ErrorResponse(problem.get()));
    }
    RecurrenceService.CreateResult result = recurrences.create(user.id(), toInput(request));
    if (result instanceof RecurrenceService.CreateResult.Created created) {
      log.info(
          "recorrência criada usuario_id={} template_id={} ocorrencias={}",
          user.id(),
          created.recurrence().id(),
          created.materialized());
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(
              new RecurrenceCreatedResponse(
                  RecurrenceResponse.from(created.recurrence()), created.materialized()));
    }
    return switch ((RecurrenceService.CreateResult.Rejected) result) {
      case ACCOUNT_NOT_FOUND -> ResponseEntity.badRequest().body(ACCOUNT_NOT_FOUND);
      case ACCOUNT_ARCHIVED -> ResponseEntity.badRequest().body(ACCOUNT_ARCHIVED);
      case CRYPTO_ACCOUNT -> ResponseEntity.badRequest().body(CRYPTO_ACCOUNT);
      case CATEGORY_NOT_FOUND -> ResponseEntity.badRequest().body(CATEGORY_NOT_FOUND);
      case CATEGORY_NOT_PERMITTED -> ResponseEntity.badRequest().body(CATEGORY_NOT_PERMITTED);
    };
  }

  @Operation(
      operationId = "getRecorrencias",
      summary = "Lista as recorrências do usuário, ativas e inativas",
      description = "Ordem por título sem diferenciar maiúscula de minúscula, depois por id.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Recorrências do usuário",
      content =
          @Content(
              array = @ArraySchema(schema = @Schema(implementation = RecurrenceResponse.class))))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/recorrencias", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<List<RecurrenceResponse>> list(@AuthenticationPrincipal AuthenticatedUser user) {
    return ResponseEntity.ok(
        recurrences.list(user.id()).stream().map(RecurrenceResponse::from).toList());
  }

  @Operation(
      operationId = "getRecorrenciasById",
      summary = "Devolve uma recorrência do usuário",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Recorrência",
      content = @Content(schema = @Schema(implementation = RecurrenceResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Recorrência de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/recorrencias/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> find(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    return CanonicalUuid.parse(id)
        .flatMap(templateId -> recurrences.find(user.id(), templateId))
        .<ResponseEntity<?>>map(found -> ResponseEntity.ok(RecurrenceResponse.from(found)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
  }

  @Operation(
      operationId = "patchRecorrenciasById",
      summary = "Desativa uma recorrência",
      description =
          "Só {\"ativo\": false}. Marca o template como inativo e apaga as ocorrências previstas,"
              + " não desgarradas, de hoje em diante; as de antes de hoje e as realizadas ou"
              + " editadas ficam. Repetir a chamada devolve o mesmo estado. Depois da resposta o"
              + " job não gera mais nada para o template.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Recorrência inativa",
      content = @Content(schema = @Schema(implementation = RecurrenceResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Corpo inválido, inclusive campo não declarado; \"conta arquivada\" ao reativar",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Recorrência de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
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
      path = "/recorrencias/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> setActive(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody ActiveRequest request) {
    Optional<UUID> templateId = CanonicalUuid.parse(id);
    if (templateId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    if (request.active()) {
      return switch (recurrences.reactivate(user.id(), templateId.get())) {
        case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
        case ACCOUNT_ARCHIVED -> ResponseEntity.badRequest().body(ACCOUNT_ARCHIVED);
        case REACTIVATED -> {
          log.info(
              "recorrência reativada usuario_id={} template_id={}", user.id(), templateId.get());
          yield recurrences
              .find(user.id(), templateId.get())
              .<ResponseEntity<?>>map(found -> ResponseEntity.ok(RecurrenceResponse.from(found)))
              .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
        }
      };
    }
    return recurrences
        .deactivate(user.id(), templateId.get())
        .<ResponseEntity<?>>map(
            found -> {
              log.info(
                  "recorrência desativada usuario_id={} template_id={}", user.id(), found.id());
              return ResponseEntity.ok(RecurrenceResponse.from(found));
            })
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
  }

  @Operation(
      operationId = "putRecorrenciasById",
      summary = "Edita uma recorrência",
      description =
          "Corpo do POST, com o mesmo tipo. As previstas de hoje em diante não editadas à mão são"
              + " geradas de novo com os dados novos se a recorrência estiver ativa; realizadas,"
              + " canceladas, editadas e vencidas ficam.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Recorrência editada e quantas ocorrências foram geradas",
      content = @Content(schema = @Schema(implementation = RecurrenceCreatedResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida como no POST, tipo diferente do atual, conta ou categoria que não"
              + " serve",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Recorrência de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
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
      description = "Banco indisponível ou conta travada por mais de 2 s",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/recorrencias/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody RecurrenceRequest request) {
    Optional<UUID> templateId = CanonicalUuid.parse(id);
    if (templateId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    if (request.type() == EntryType.TRANSFER) {
      return ResponseEntity.badRequest().body(INVALID_TYPE);
    }
    Optional<String> problem = request.crossFieldProblem();
    if (problem.isPresent()) {
      return ResponseEntity.badRequest().body(new ErrorResponse(problem.get()));
    }
    RecurrenceService.UpdateResult result =
        recurrences.update(user.id(), templateId.get(), toInput(request));
    if (result instanceof RecurrenceService.UpdateResult.Updated updated) {
      log.info(
          "recorrência editada usuario_id={} template_id={} ocorrencias={}",
          user.id(),
          templateId.get(),
          updated.materialized());
      return ResponseEntity.ok(
          new RecurrenceCreatedResponse(
              RecurrenceResponse.from(updated.recurrence()), updated.materialized()));
    }
    return switch ((RecurrenceService.UpdateResult.Rejected) result) {
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case TYPE_CHANGED -> ResponseEntity.badRequest().body(TYPE_CHANGED);
      case ACCOUNT_NOT_FOUND -> ResponseEntity.badRequest().body(ACCOUNT_NOT_FOUND);
      case ACCOUNT_ARCHIVED -> ResponseEntity.badRequest().body(ACCOUNT_ARCHIVED);
      case CRYPTO_ACCOUNT -> ResponseEntity.badRequest().body(CRYPTO_ACCOUNT);
      case CATEGORY_NOT_FOUND -> ResponseEntity.badRequest().body(CATEGORY_NOT_FOUND);
      case CATEGORY_NOT_PERMITTED -> ResponseEntity.badRequest().body(CATEGORY_NOT_PERMITTED);
    };
  }

  @Operation(
      operationId = "deleteRecorrenciasById",
      summary = "Apaga uma recorrência",
      description =
          "Apaga a recorrência e todas as previstas dela, inclusive as vencidas. Realizadas e"
              + " canceladas ficam como lançamentos avulsos.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Apagada")
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Recorrência de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/recorrencias/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> templateId = CanonicalUuid.parse(id);
    if (templateId.isEmpty() || !recurrences.delete(user.id(), templateId.get())) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    log.info("recorrência apagada usuario_id={} template_id={}", user.id(), templateId.get());
    return ResponseEntity.noContent().build();
  }

  // Id que não é UUID vira nulo, e o serviço responde como conta ou categoria que não existe.
  private static RecurrenceInput toInput(RecurrenceRequest request) {
    Division division = request.division() == null ? null : request.division().toDivision();
    return new RecurrenceInput(
        request.type(),
        request.title(),
        request.description(),
        request.amount(),
        CanonicalUuid.parse(request.categoryId()).orElse(null),
        CanonicalUuid.parse(request.accountId()).orElse(null),
        request.rule().toRule(division),
        division);
  }
}
