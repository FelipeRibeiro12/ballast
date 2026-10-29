package com.felipe.ballast.imports;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser). Importação, linha, conta e lançamento de outro
// usuário respondem 404 com o mesmo corpo do inexistente. O log leva só ids e contagens: linha,
// memo e FITID nunca entram nele.
@Tag(name = "importacoes")
@RestController
class ImportController {

  private static final Logger log = LoggerFactory.getLogger(ImportController.class);

  private static final ErrorResponse NOT_FOUND = new ErrorResponse("importação não encontrada");
  private static final ErrorResponse ACCOUNT_NOT_FOUND = new ErrorResponse("conta não encontrada");
  private static final ErrorResponse ACCOUNT_ARCHIVED = new ErrorResponse("conta arquivada");
  private static final ErrorResponse ACCOUNT_NOT_ACCEPTED =
      new ErrorResponse("conta cripto ou de investimento não recebe lançamento");
  private static final ErrorResponse INVALID_ACCOUNT_PARAMETER =
      new ErrorResponse("conta_id é obrigatório e deve ser um UUID");
  private static final ErrorResponse LINE_NOT_FOUND = new ErrorResponse("linha não encontrada");
  private static final ErrorResponse ENTRY_NOT_FOUND =
      new ErrorResponse("lançamento não encontrado");
  private static final ErrorResponse CATEGORY_NOT_FOUND =
      new ErrorResponse("categoria não encontrada");
  private static final ErrorResponse CATEGORY_TYPE_MISMATCH =
      new ErrorResponse("categoria de outro tipo");
  private static final ErrorResponse LINE_NOT_EDITABLE =
      new ErrorResponse("linha já ignorada pela proposta só pode ser ignorada");
  private static final ErrorResponse INVALID_DECISION =
      new ErrorResponse(
          "conciliar exige lancamentoId; categoriaId e titulo só valem com importar;"
              + " lancamentoId só vale com conciliar");
  private static final ErrorResponse ALREADY_CONFIRMED =
      new ErrorResponse("importação já confirmada");

  private final ImportService service;

  ImportController(ImportService service) {
    this.service = service;
  }

  @Operation(
      operationId = "postImportacoes",
      summary = "Envia um extrato OFX e devolve a proposta por linha",
      description =
          "O corpo é o arquivo cru (application/octet-stream), de até 1 MiB e 2000 transações."
              + " Nada vira lançamento até a confirmação. O mesmo arquivo duas vezes devolve 409"
              + " com o importacaoId da primeira; descartar a pendente libera o arquivo.",
      security = @SecurityRequirement(name = "cookieAuth"),
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                      schema = @Schema(type = "string", format = "binary"))))
  @ApiResponse(
      responseCode = "201",
      description = "Importação pendente criada",
      content = @Content(schema = @Schema(implementation = ImportDetailResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "conta_id ausente ou que não é UUID, \"conta arquivada\" ou \"conta cripto ou de"
              + " investimento não recebe lançamento\"",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Conta de outro usuário ou inexistente; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description =
          "Arquivo já enviado (corpo com importacaoId) ou 20 importações pendentes (corpo"
              + " comum de erro)",
      content = @Content(schema = @Schema(implementation = DuplicateImportResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "Arquivo maior que 1 MiB",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "415",
      description = "Content-Type diferente de application/octet-stream",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "422",
      description =
          "Arquivo vazio, que não é OFX, com DOCTYPE ou ENTITY, sem transações ou com mais de"
              + " 2000",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/importacoes",
      consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> upload(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(type = "string", format = "uuid") @RequestParam(name = "conta_id", required = false)
          String accountId,
      @RequestBody(required = false) byte[] content) {
    Optional<UUID> account = CanonicalUuid.parse(accountId);
    if (account.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_ACCOUNT_PARAMETER);
    }
    ImportService.Result<ImportService.Detail> result =
        service.upload(user.id(), account.get(), content);
    if (result instanceof ImportService.Result.Ok<ImportService.Detail> ok) {
      ImportDetailResponse body =
          ImportDetailResponse.from(ok.value().header(), ok.value().lines());
      log.info(
          "importação criada usuario_id={} importacao_id={} linhas={}",
          user.id(),
          body.id(),
          body.totalLinhas());
      return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
    return failure((ImportService.Result.Failed<?>) result);
  }

  @Operation(
      operationId = "getImportacoes",
      summary = "Lista as importações do usuário, da mais nova para a mais antiga",
      description = "No máximo 100, sem as linhas.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Importações do usuário",
      content =
          @Content(array = @ArraySchema(schema = @Schema(implementation = ImportResponse.class))))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/importacoes", produces = MediaType.APPLICATION_JSON_VALUE)
  List<ImportResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
    return service.list(user.id()).stream().map(ImportResponse::from).toList();
  }

  @Operation(
      operationId = "getImportacoesById",
      summary = "Importação com todas as linhas e a proposta de cada uma",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Importação com as linhas",
      content = @Content(schema = @Schema(implementation = ImportDetailResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Importação de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/importacoes/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> find(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> importId = CanonicalUuid.parse(id);
    if (importId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return service
        .find(user.id(), importId.get())
        .<ResponseEntity<?>>map(
            detail -> ResponseEntity.ok(ImportDetailResponse.from(detail.header(), detail.lines())))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
  }

  @Operation(
      operationId = "deleteImportacoesById",
      summary = "Descarta uma importação pendente",
      description = "Libera o arquivo para um novo envio. A confirmada não se descarta.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Importação descartada")
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Importação de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Importação já confirmada",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/importacoes/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> discard(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> importId = CanonicalUuid.parse(id);
    if (importId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    ImportService.Result<Boolean> result = service.discard(user.id(), importId.get());
    if (result instanceof ImportService.Result.Ok<Boolean>) {
      log.info("importação descartada usuario_id={} importacao_id={}", user.id(), importId.get());
      return ResponseEntity.noContent().build();
    }
    return failure((ImportService.Result.Failed<?>) result);
  }

  @Operation(
      operationId = "putImportacoesByIdLinhasByLinhaId",
      summary = "Muda a decisão de uma linha da importação pendente",
      description =
          "acao importar aceita categoriaId e titulo; conciliar exige lancamentoId de um lançamento"
              + " realizado da mesma conta, ainda sem vínculo de importação; ignorar não leva"
              + " nada. Sem decisão vale a proposta. Linha que a proposta já ignorou (inválida,"
              + " repetida no arquivo ou já importada) só aceita ignorar.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Linha com a decisão nova",
      content = @Content(schema = @Schema(implementation = ImportLineResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, inclusive campo não declarado; campos que não pertencem à ação;"
              + " categoria de outro tipo; linha que só aceita ignorar",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Importação, linha, lançamento ou categoria de outro usuário, inexistente ou id que não"
              + " é UUID; mesmo corpo para os três casos de cada um",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Importação já confirmada",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/importacoes/{id}/linhas/{linhaId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> decide(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @PathVariable("linhaId") String lineId,
      @Valid @RequestBody ImportLineRequest request) {
    Optional<UUID> importId = CanonicalUuid.parse(id);
    Optional<UUID> line = CanonicalUuid.parse(lineId);
    if (importId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    if (line.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(LINE_NOT_FOUND);
    }
    boolean reconcile = request.action() == LineAction.RECONCILE;
    boolean importing = request.action() == LineAction.IMPORT;
    if ((reconcile != (request.entryId() != null))
        || (!importing && (request.categoryId() != null || request.title() != null))) {
      return ResponseEntity.badRequest().body(INVALID_DECISION);
    }
    UUID entryId = null;
    if (request.entryId() != null) {
      entryId = CanonicalUuid.parse(request.entryId()).orElse(null);
      if (entryId == null) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ENTRY_NOT_FOUND);
      }
    }
    UUID categoryId = null;
    if (request.categoryId() != null) {
      categoryId = CanonicalUuid.parse(request.categoryId()).orElse(null);
      if (categoryId == null) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(CATEGORY_NOT_FOUND);
      }
    }
    ImportService.Result<ImportQueries.LineRow> result =
        service.decide(
            user.id(),
            importId.get(),
            line.get(),
            new ImportService.LineDecision(
                request.action().value(), entryId, categoryId, request.title()));
    if (result instanceof ImportService.Result.Ok<ImportQueries.LineRow> ok) {
      log.info(
          "linha de importação decidida usuario_id={} importacao_id={} linha_id={} acao={}",
          user.id(),
          importId.get(),
          line.get(),
          request.action().value());
      return ResponseEntity.ok(ImportLineResponse.from(ok.value()));
    }
    return failure((ImportService.Result.Failed<?>) result);
  }

  @Operation(
      operationId = "postImportacoesByIdConfirmacao",
      summary = "Confirma a importação e cria os lançamentos",
      description =
          "Sem corpo. Cada linha vale pela decisão ou, sem ela, pela proposta: importar cria o"
              + " lançamento pelas mesmas regras do POST /lancamentos (dataCompetencia é a data do"
              + " arquivo; dataCaixa segue a conta), conciliar só grava o vínculo no lançamento"
              + " existente e ignorar não grava nada. Linha cujo FITID entrou no meio-tempo"
              + " conta como duplicada. Tudo numa transação: uma recusa de regra desfaz a"
              + " confirmação inteira. Aceita Idempotency-Key (ADR-056).",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Importação confirmada, com a contagem por resultado",
      content = @Content(schema = @Schema(implementation = ConfirmationResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Recusa de regra ao criar um lançamento (conta arquivada, categoria não encontrada,"
              + " categoria não permitida nesta conta, saldo da conta fora do limite) ou"
              + " Idempotency-Key inválida. Nada é criado",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Importação de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Importação já confirmada",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(path = "/importacoes/{id}/confirmacao", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> confirm(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> importId = CanonicalUuid.parse(id);
    if (importId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    ImportService.Result<ImportService.Confirmation> result =
        service.confirm(user.id(), importId.get());
    if (result instanceof ImportService.Result.Ok<ImportService.Confirmation> ok) {
      ImportService.Confirmation done = ok.value();
      log.info(
          "importação confirmada usuario_id={} importacao_id={} criadas={} conciliadas={}"
              + " ignoradas={} duplicadas={}",
          user.id(),
          importId.get(),
          done.created(),
          done.reconciled(),
          done.ignored(),
          done.duplicated());
      return ResponseEntity.ok(ConfirmationResponse.from(done));
    }
    return failure((ImportService.Result.Failed<?>) result);
  }

  private static ResponseEntity<?> failure(ImportService.Result.Failed<?> failed) {
    return switch (failed.failure()) {
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case ACCOUNT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ACCOUNT_NOT_FOUND);
      case ACCOUNT_ARCHIVED -> ResponseEntity.badRequest().body(ACCOUNT_ARCHIVED);
      case ACCOUNT_NOT_ACCEPTED -> ResponseEntity.badRequest().body(ACCOUNT_NOT_ACCEPTED);
      case INVALID_FILE ->
          ResponseEntity.unprocessableEntity().body(new ErrorResponse(failed.message()));
      case DUPLICATE_FILE ->
          ResponseEntity.status(HttpStatus.CONFLICT)
              .body(new DuplicateImportResponse("arquivo já importado", failed.importId()));
      case ALREADY_CONFIRMED -> ResponseEntity.status(HttpStatus.CONFLICT).body(ALREADY_CONFIRMED);
      case LINE_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(LINE_NOT_FOUND);
      case ENTRY_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ENTRY_NOT_FOUND);
      case CATEGORY_NOT_FOUND ->
          ResponseEntity.status(HttpStatus.NOT_FOUND).body(CATEGORY_NOT_FOUND);
      case CATEGORY_TYPE_MISMATCH -> ResponseEntity.badRequest().body(CATEGORY_TYPE_MISMATCH);
      case LINE_NOT_EDITABLE -> ResponseEntity.badRequest().body(LINE_NOT_EDITABLE);
      case ENTRY_REFUSED -> ResponseEntity.badRequest().body(new ErrorResponse(failed.message()));
    };
  }
}
