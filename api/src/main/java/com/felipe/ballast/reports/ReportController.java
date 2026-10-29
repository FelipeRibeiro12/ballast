package com.felipe.ballast.reports;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.ErrorResponse;
import com.felipe.ballast.web.Months;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "relatorios")
@RestController
class ReportController {

  private static final ErrorResponse INVALID_MONTH =
      new ErrorResponse("mes deve ser AAAA-MM com ano entre 1900 e 2199");
  private static final ErrorResponse OUT_OF_RANGE = new ErrorResponse("relatório fora do limite");

  private final ReportService reports;

  ReportController(ReportService reports) {
    this.reports = reports;
  }

  @Operation(
      operationId = "getRelatorios",
      summary = "Despesa por categoria e receita contra despesa",
      description =
          "Por competência e só o realizado até hoje (ADR-046). Parcela de compra parcelada conta no mês"
              + " dela e pelo valor dela (ADR-074). Sem mes, vale o mês corrente em America/Sao_Paulo.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Relatório do mês",
      content = @Content(schema = @Schema(implementation = ReportResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "mes fora do formato ou \"relatório fora do limite\"",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/relatorios", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> monthly(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(pattern = Months.SHAPE) @RequestParam(name = "mes", required = false) String month) {
    Optional<YearMonth> parsed =
        month == null ? Optional.of(reports.currentMonth()) : Months.parse(month);
    if (parsed.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_MONTH);
    }
    return reports
        .monthly(user.id(), parsed.get())
        .<ResponseEntity<?>>map(report -> ResponseEntity.ok(ReportResponse.from(report)))
        .orElseGet(() -> ResponseEntity.badRequest().body(OUT_OF_RANGE));
  }
}
