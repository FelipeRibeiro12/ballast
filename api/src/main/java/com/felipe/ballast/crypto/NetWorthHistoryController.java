package com.felipe.ballast.crypto;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.ErrorResponse;
import com.felipe.ballast.web.Months;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDate;
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
class NetWorthHistoryController {

  // Doze pontos de linha cabem em 360px com o rótulo do mês a cada três (ADR-047).
  static final int MONTHS = 12;

  private static final ErrorResponse INVALID_MONTH =
      new ErrorResponse("mes deve ser AAAA-MM com ano entre 1900 e 2199");
  private static final ErrorResponse OUT_OF_RANGE = new ErrorResponse("patrimônio fora do limite");

  private final NetWorthService netWorth;
  private final Clock clock;

  NetWorthHistoryController(NetWorthService netWorth, Clock clock) {
    this.netWorth = netWorth;
    this.clock = clock;
  }

  @Operation(
      operationId = "getPatrimonioMensal",
      summary = "Patrimônio no fim de cada mês",
      description =
          "O patrimônio de /patrimonio no último dia de cada um dos doze meses que terminam em"
              + " mes, e hoje no mês corrente. Sem mes, vale o mês corrente em America/Sao_Paulo.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Patrimônio mês a mês",
      content = @Content(schema = @Schema(implementation = NetWorthHistoryResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "mes fora do formato ou \"patrimônio fora do limite\"",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/patrimonio/mensal", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> monthly(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(pattern = Months.SHAPE) @RequestParam(name = "mes", required = false) String month) {
    LocalDate today = clock.instant().atZone(NetWorthService.ZONE).toLocalDate();
    Optional<YearMonth> parsed =
        month == null ? Optional.of(YearMonth.from(today)) : Months.parse(month);
    if (parsed.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_MONTH);
    }
    return netWorth
        .monthly(user.id(), parsed.get(), MONTHS, today)
        .<ResponseEntity<?>>map(
            months -> ResponseEntity.ok(NetWorthHistoryResponse.from(parsed.get(), months)))
        .orElseGet(() -> ResponseEntity.badRequest().body(OUT_OF_RANGE));
  }
}
