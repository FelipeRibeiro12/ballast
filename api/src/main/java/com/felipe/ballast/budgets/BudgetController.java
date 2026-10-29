package com.felipe.ballast.budgets;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.CanonicalUuid;
import com.felipe.ballast.web.ErrorResponse;
import com.felipe.ballast.web.Months;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.YearMonth;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "orcamentos")
@RestController
class BudgetController {

  private static final Logger log = LoggerFactory.getLogger(BudgetController.class);

  private static final ErrorResponse NOT_FOUND = new ErrorResponse("categoria não encontrada");
  private static final ErrorResponse NOT_EXPENSE =
      new ErrorResponse("orçamento só vale para categoria de despesa");
  private static final ErrorResponse INVALID_MONTH =
      new ErrorResponse("mes deve ser AAAA-MM com ano entre 1900 e 2199");
  private static final ErrorResponse INVALID_FROM = new ErrorResponse(BudgetRequest.MONTH_MESSAGE);
  private static final ErrorResponse OUT_OF_RANGE = new ErrorResponse("orçamento fora do limite");

  private final BudgetService budgets;

  BudgetController(BudgetService budgets) {
    this.budgets = budgets;
  }

  @Operation(
      operationId = "getOrcamentos",
      summary = "Orçamento do mês por categoria",
      description =
          "Cada categoria com teto no mês, com o gasto por competência, o previsto e o que falta"
              + " (ADR-043). Parcela de compra parcelada conta no mês dela e pelo valor dela"
              + " (ADR-075). Sem mes, vale o mês corrente em America/Sao_Paulo.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Orçamentos do mês",
      content = @Content(schema = @Schema(implementation = BudgetListResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "mes fora do formato ou \"orçamento fora do limite\"",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/orcamentos", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(pattern = Months.SHAPE) @RequestParam(name = "mes", required = false) String month) {
    Optional<YearMonth> parsed =
        month == null ? Optional.of(budgets.currentMonth()) : Months.parse(month);
    if (parsed.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_MONTH);
    }
    Optional<List<BudgetService.Budget>> found = budgets.list(user.id(), parsed.get());
    if (found.isEmpty()) {
      return ResponseEntity.badRequest().body(OUT_OF_RANGE);
    }
    return ResponseEntity.ok(BudgetListResponse.from(parsed.get(), found.get()));
  }

  @Operation(
      operationId = "putOrcamentosByCategoriaId",
      summary = "Define o teto da categoria a partir de um mês",
      description =
          "Vale do mês aPartirDe até a próxima mudança da mesma categoria. Definir de novo no"
              + " mesmo mês troca o teto.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Teto gravado")
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida ou categoria de receita",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Categoria de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/orcamentos/{categoriaId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> set(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("categoriaId") String categoryId,
      @Valid @RequestBody BudgetRequest request) {
    return save(user, categoryId, request.parsedMonth(), request.ceiling());
  }

  @Operation(
      operationId = "deleteOrcamentosByCategoriaId",
      summary = "Encerra o orçamento da categoria a partir de um mês",
      description =
          "Os meses antes de aPartirDe continuam com o teto que tinham. Uma mudança posterior"
              + " da mesma categoria volta a valer no mês dela.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Orçamento encerrado")
  @ApiResponse(
      responseCode = "400",
      description = "aPartirDe fora do formato ou categoria de receita",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Categoria de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/orcamentos/{categoriaId}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> remove(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("categoriaId") String categoryId,
      @Schema(pattern = Months.SHAPE) @RequestParam(name = "aPartirDe", required = false)
          String fromMonth) {
    Optional<YearMonth> from = Months.parse(fromMonth);
    if (from.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_FROM);
    }
    return save(user, categoryId, from.get(), null);
  }

  private ResponseEntity<?> save(
      AuthenticatedUser user, String categoryId, YearMonth from, BigDecimal ceiling) {
    Optional<UUID> id = CanonicalUuid.parse(categoryId);
    if (id.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return switch (budgets.set(user.id(), id.get(), from, ceiling)) {
      case SAVED -> {
        log.info(
            "orçamento gravado usuario_id={} categoria_id={} desde={} encerrado={}",
            user.id(),
            id.get(),
            from,
            ceiling == null);
        yield ResponseEntity.noContent().build();
      }
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case NOT_EXPENSE -> ResponseEntity.badRequest().body(NOT_EXPENSE);
    };
  }
}
