package com.felipe.ballast.fixedincome;

import com.felipe.ballast.entries.IsoDates;
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
import java.time.LocalDate;
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
@Tag(name = "renda-fixa")
@RestController
class FixedIncomeController {

  private static final Logger log = LoggerFactory.getLogger(FixedIncomeController.class);

  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");
  private static final ErrorResponse ACCOUNT_NOT_FOUND = new ErrorResponse("conta não encontrada");
  private static final ErrorResponse NOT_INVESTMENT =
      new ErrorResponse("conta deve ser de investimento");
  private static final ErrorResponse ACCOUNT_ARCHIVED = new ErrorResponse("conta arquivada");
  private static final ErrorResponse FUTURE_APPLICATION =
      new ErrorResponse("dataAplicacao não pode ser no futuro");
  private static final ErrorResponse MATURITY_BEFORE =
      new ErrorResponse("vencimento deve ser depois da aplicação");
  private static final ErrorResponse APPLICATION_AFTER_REDEMPTION =
      new ErrorResponse("dataAplicacao não pode ser depois do resgate");
  private static final ErrorResponse DATE_OUT_OF_RANGE =
      new ErrorResponse("data deve ficar entre a aplicação e hoje");
  private static final ErrorResponse REDEEMED = new ErrorResponse("título já resgatado nessa data");
  private static final ErrorResponse INVALID_DATE =
      new ErrorResponse("data deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199");
  private static final ErrorResponse OUT_OF_RANGE = new ErrorResponse("renda fixa fora do limite");

  private final FixedIncomeService titles;

  FixedIncomeController(FixedIncomeService titles) {
    this.titles = titles;
  }

  @Operation(
      operationId = "postTitulosRendaFixa",
      summary = "Cadastra um título de renda fixa numa conta de investimento",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Título criado",
      content = @Content(schema = @Schema(implementation = TitleResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, aplicação no futuro, vencimento antes da aplicação, conta"
              + " inexistente, alheia, de outro tipo ou arquivada",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description =
          "Teto de títulos do usuário atingido (limite de títulos de renda fixa atingido)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/titulos-renda-fixa",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody TitleRequest request) {
    Optional<ErrorResponse> problem = crossFieldProblem(request);
    if (problem.isPresent()) {
      return ResponseEntity.badRequest().body(problem.get());
    }
    return respond(user, titles.create(user.id(), fields(request)), HttpStatus.CREATED);
  }

  @Operation(
      operationId = "putTitulosRendaFixaById",
      summary = "Edita um título de renda fixa",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Título editado",
      content = @Content(schema = @Schema(implementation = TitleResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Como no POST, e aplicação depois do resgate (no mesmo dia vale)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Título de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/titulos-renda-fixa/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody TitleRequest request) {
    Optional<UUID> titleId = CanonicalUuid.parse(id);
    if (titleId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    Optional<ErrorResponse> problem = crossFieldProblem(request);
    if (problem.isPresent()) {
      return ResponseEntity.badRequest().body(problem.get());
    }
    return respond(user, titles.update(user.id(), titleId.get(), fields(request)), HttpStatus.OK);
  }

  @Operation(
      operationId = "deleteTitulosRendaFixaById",
      summary = "Apaga um título de renda fixa e as marcações dele",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Apagado")
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Título de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/titulos-renda-fixa/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> titleId = CanonicalUuid.parse(id);
    if (titleId.isEmpty() || !titles.delete(user.id(), titleId.get())) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    log.info("título de renda fixa apagado usuario_id={} titulo_id={}", user.id(), titleId.get());
    return ResponseEntity.noContent().build();
  }

  @Operation(
      operationId = "getTitulosRendaFixa",
      summary = "Lista os títulos com o valor numa data",
      description =
          "Valor bruto, IOF e IR estimados (só CDB), líquido e rendimento na data. Nulos antes da"
              + " aplicação; zero depois do resgate. Sem data, vale hoje em America/Sao_Paulo.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Títulos das contas ativas",
      content = @Content(schema = @Schema(implementation = TitleListResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "data fora do formato ou \"renda fixa fora do limite\"",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/titulos-renda-fixa", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(type = "string", format = "date") @RequestParam(name = "data", required = false)
          String date) {
    Optional<LocalDate> day = date == null ? Optional.of(titles.today()) : IsoDates.parse(date);
    if (day.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_DATE);
    }
    return titles
        .list(user.id(), day.get())
        .<ResponseEntity<?>>map(
            found ->
                ResponseEntity.ok(
                    new TitleListResponse(
                        day.get(),
                        new IndicesResponse(found.cdiUntil(), found.ipcaUntil()),
                        found.titles().stream()
                            .map(item -> TitleResponse.from(item.title(), item.valuation()))
                            .toList())))
        .orElseGet(() -> ResponseEntity.badRequest().body(OUT_OF_RANGE));
  }

  @Operation(
      operationId = "postTitulosRendaFixaByIdMarcacoes",
      summary = "Grava o valor que o banco mostrou numa data",
      description =
          "Vira o ponto de partida do cálculo dali em diante. Outra no mesmo dia troca o valor.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "201", description = "Marcação gravada")
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida, data antes da aplicação, no futuro ou depois do resgate",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Título de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/titulos-renda-fixa/{id}/marcacoes",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> mark(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody DatedValueRequest request) {
    Optional<UUID> titleId = CanonicalUuid.parse(id);
    if (titleId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return switch (titles.mark(user.id(), titleId.get(), request.parsedDate(), request.value())) {
      case SAVED -> {
        log.info("marcação gravada usuario_id={} titulo_id={}", user.id(), titleId.get());
        yield ResponseEntity.status(HttpStatus.CREATED).build();
      }
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case DATE_OUT_OF_RANGE -> ResponseEntity.badRequest().body(DATE_OUT_OF_RANGE);
      case REDEEMED -> ResponseEntity.badRequest().body(REDEEMED);
    };
  }

  @Operation(
      operationId = "postTitulosRendaFixaByIdResgate",
      summary = "Registra o resgate do título",
      description = "Data e valor recebido. Resgatar de novo troca os dois.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Título com o resgate",
      content = @Content(schema = @Schema(implementation = TitleResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida, data antes da aplicação ou no futuro",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Título de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/titulos-renda-fixa/{id}/resgate",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> redeem(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody DatedValueRequest request) {
    Optional<UUID> titleId = CanonicalUuid.parse(id);
    if (titleId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return switch (titles.redeem(user.id(), titleId.get(), request.parsedDate(), request.value())) {
      case SAVED -> {
        log.info("título resgatado usuario_id={} titulo_id={}", user.id(), titleId.get());
        yield titles
            .find(user.id(), titleId.get())
            .<ResponseEntity<?>>map(found -> ResponseEntity.ok(TitleResponse.from(found, null)))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
      }
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case DATE_OUT_OF_RANGE, REDEEMED -> ResponseEntity.badRequest().body(DATE_OUT_OF_RANGE);
    };
  }

  @Operation(
      operationId = "getIndices",
      summary = "Até quando há CDI e IPCA guardados",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Última data de cada série",
      content = @Content(schema = @Schema(implementation = IndicesResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/indices", produces = MediaType.APPLICATION_JSON_VALUE)
  IndicesResponse indices() {
    return new IndicesResponse(titles.cdiUntil().orElse(null), titles.ipcaUntil().orElse(null));
  }

  private Optional<ErrorResponse> crossFieldProblem(TitleRequest request) {
    LocalDate application = request.parsedApplicationDate();
    if (application.isAfter(titles.today())) {
      return Optional.of(FUTURE_APPLICATION);
    }
    LocalDate maturity = request.parsedMaturity();
    if (maturity != null && !maturity.isAfter(application)) {
      return Optional.of(MATURITY_BEFORE);
    }
    return Optional.empty();
  }

  // Id de conta que não é UUID vira nulo, e o serviço responde "conta não encontrada".
  private static FixedIncomeQueries.Fields fields(TitleRequest request) {
    return new FixedIncomeQueries.Fields(
        CanonicalUuid.parse(request.accountId()).orElse(null),
        request.type(),
        request.name(),
        request.invested(),
        request.parsedApplicationDate(),
        request.parsedMaturity(),
        request.indexer(),
        request.parsedRate());
  }

  private static ResponseEntity<?> respond(
      AuthenticatedUser user, FixedIncomeService.SaveResult result, HttpStatus success) {
    return switch (result) {
      case FixedIncomeService.SaveResult.Saved saved -> {
        log.info(
            "título de renda fixa gravado usuario_id={} titulo_id={}",
            user.id(),
            saved.title().id());
        yield ResponseEntity.status(success).body(TitleResponse.from(saved.title(), null));
      }
      case FixedIncomeService.SaveResult.Rejected rejected -> {
        log.warn("título de renda fixa recusado: motivo={} usuario_id={}", rejected, user.id());
        yield switch (rejected) {
          case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
          case ACCOUNT_NOT_FOUND -> ResponseEntity.badRequest().body(ACCOUNT_NOT_FOUND);
          case NOT_INVESTMENT -> ResponseEntity.badRequest().body(NOT_INVESTMENT);
          case ACCOUNT_ARCHIVED -> ResponseEntity.badRequest().body(ACCOUNT_ARCHIVED);
          case APPLICATION_AFTER_REDEMPTION ->
              ResponseEntity.badRequest().body(APPLICATION_AFTER_REDEMPTION);
        };
      }
    };
  }
}
