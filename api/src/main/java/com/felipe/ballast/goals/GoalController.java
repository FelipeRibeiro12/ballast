package com.felipe.ballast.goals;

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

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "metas")
@RestController
class GoalController {

  private static final Logger log = LoggerFactory.getLogger(GoalController.class);

  private static final ErrorResponse NOT_FOUND = new ErrorResponse("meta não encontrada");
  private static final ErrorResponse ZERO = new ErrorResponse(DepositRequest.VALUE_MESSAGE);
  private static final ErrorResponse FUTURE =
      new ErrorResponse("data do aporte não pode ser no futuro");
  private static final ErrorResponse WITHDRAWAL_TOO_BIG =
      new ErrorResponse("retirada maior que o acumulado da meta");
  private static final ErrorResponse TRANSFER_NOT_FOUND =
      new ErrorResponse("transferenciaId não encontrada");
  private static final ErrorResponse TRANSFER_LINKED =
      new ErrorResponse("transferência já vinculada a outro aporte");
  private static final ErrorResponse OUT_OF_RANGE =
      new ErrorResponse("acumulado da meta fora do limite");

  private final GoalService goals;

  GoalController(GoalService goals) {
    this.goals = goals;
  }

  @Operation(
      operationId = "getMetas",
      summary = "Lista as metas com o acumulado e a projeção",
      description = "Pelo título sem diferenciar caixa (ADR-045).",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Metas do usuário",
      content =
          @Content(array = @ArraySchema(schema = @Schema(implementation = GoalResponse.class))))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/metas", produces = MediaType.APPLICATION_JSON_VALUE)
  List<GoalResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
    return goals.list(user.id()).stream().map(GoalResponse::from).toList();
  }

  @Operation(
      operationId = "postMetas",
      summary = "Cria uma meta",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Meta criada",
      content = @Content(schema = @Schema(implementation = GoalResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/metas",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<GoalResponse> create(
      @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody GoalRequest request) {
    GoalService.ProjectedGoal created = goals.create(user.id(), request.fields());
    log.info("meta criada usuario_id={} meta_id={}", user.id(), created.goal().id());
    return ResponseEntity.status(HttpStatus.CREATED).body(GoalResponse.from(created));
  }

  @Operation(
      operationId = "putMetasById",
      summary = "Troca título, alvo, data e aporte mensal da meta",
      description = "Os aportes ficam. dataAlvo e aporteMensalCentavos ausentes ficam sem valor.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Meta atualizada",
      content = @Content(schema = @Schema(implementation = GoalResponse.class)))
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
      description = "Meta de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/metas/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody GoalRequest request) {
    Optional<UUID> goalId = CanonicalUuid.parse(id);
    Optional<GoalService.ProjectedGoal> updated =
        goalId.flatMap(found -> goals.update(user.id(), found, request.fields()));
    if (updated.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return ResponseEntity.ok(GoalResponse.from(updated.get()));
  }

  @Operation(
      operationId = "deleteMetasById",
      summary = "Apaga a meta e os aportes dela",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Meta apagada")
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Meta de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/metas/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> goalId = CanonicalUuid.parse(id);
    if (goalId.isEmpty() || !goals.delete(user.id(), goalId.get())) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    log.info("meta apagada usuario_id={} meta_id={}", user.id(), goalId.get());
    return ResponseEntity.noContent().build();
  }

  @Operation(
      operationId = "getMetasByIdAportes",
      summary = "Aportes e retiradas da meta, do mais novo ao mais antigo",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Aportes",
      content =
          @Content(array = @ArraySchema(schema = @Schema(implementation = DepositResponse.class))))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Meta de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/metas/{id}/aportes", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> deposits(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<List<GoalQueries.Deposit>> found =
        CanonicalUuid.parse(id).flatMap(goalId -> goals.deposits(user.id(), goalId));
    if (found.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return ResponseEntity.ok(found.get().stream().map(DepositResponse::from).toList());
  }

  @Operation(
      operationId = "postMetasByIdAportes",
      summary = "Registra um aporte (positivo) ou uma retirada (negativo)",
      description =
          "Data até hoje em America/Sao_Paulo. A retirada não pode passar do acumulado."
              + " Responde a meta já com o aporte.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Meta com o aporte",
      content = @Content(schema = @Schema(implementation = GoalResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, data no futuro, retirada maior que o acumulado ou \"transferenciaId"
              + " não encontrada\" (de outro usuário, inexistente ou que não é UUID)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "A transferência já está ligada a outro aporte",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Meta de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Escrita concorrente esperou a trava além do prazo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/metas/{id}/aportes",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> deposit(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody DepositRequest request) {
    Optional<UUID> goalId = CanonicalUuid.parse(id);
    if (goalId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    if (request.cents() == 0) {
      return ResponseEntity.badRequest().body(ZERO);
    }
    UUID transferId = null;
    if (request.transferId() != null) {
      transferId = request.parsedTransferId().orElse(null);
      if (transferId == null) {
        return ResponseEntity.badRequest().body(TRANSFER_NOT_FOUND);
      }
    }
    return switch (goals.deposit(
        user.id(), goalId.get(), request.value(), request.parsedDate(), transferId)) {
      case SAVED -> {
        log.info("aporte gravado usuario_id={} meta_id={}", user.id(), goalId.get());
        yield goals
            .find(user.id(), goalId.get())
            .<ResponseEntity<?>>map(
                goal -> ResponseEntity.status(HttpStatus.CREATED).body(GoalResponse.from(goal)))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
      }
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case FUTURE -> ResponseEntity.badRequest().body(FUTURE);
      case WITHDRAWAL_TOO_BIG -> ResponseEntity.badRequest().body(WITHDRAWAL_TOO_BIG);
      case OUT_OF_RANGE -> ResponseEntity.badRequest().body(OUT_OF_RANGE);
      case TRANSFER_NOT_FOUND -> ResponseEntity.badRequest().body(TRANSFER_NOT_FOUND);
      case TRANSFER_LINKED -> ResponseEntity.status(HttpStatus.CONFLICT).body(TRANSFER_LINKED);
    };
  }

  @Operation(
      operationId = "deleteMetasByIdAportesByAporteId",
      summary = "Apaga um aporte ou uma retirada",
      description = "Recusado quando deixaria o acumulado negativo.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Aporte apagado")
  @ApiResponse(
      responseCode = "400",
      description = "O acumulado ficaria negativo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Meta ou aporte de outro usuário, inexistente ou id que não é UUID",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(
      path = "/metas/{id}/aportes/{aporteId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> removeDeposit(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @PathVariable("aporteId") String depositId) {
    Optional<UUID> goalId = CanonicalUuid.parse(id);
    Optional<UUID> deposit = CanonicalUuid.parse(depositId);
    if (goalId.isEmpty() || deposit.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return switch (goals.removeDeposit(user.id(), goalId.get(), deposit.get())) {
      case SAVED -> {
        log.info("aporte apagado usuario_id={} meta_id={}", user.id(), goalId.get());
        yield ResponseEntity.noContent().build();
      }
      case NOT_FOUND, FUTURE -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case WITHDRAWAL_TOO_BIG -> ResponseEntity.badRequest().body(WITHDRAWAL_TOO_BIG);
      case OUT_OF_RANGE -> ResponseEntity.badRequest().body(OUT_OF_RANGE);
      case TRANSFER_NOT_FOUND, TRANSFER_LINKED ->
          throw new IllegalStateException("resultado impossível");
    };
  }
}
