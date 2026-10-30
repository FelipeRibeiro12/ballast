package com.felipe.ballast.crypto;

import com.felipe.ballast.entries.IsoDates;
import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "cripto")
@RestController
class NetWorthController {

  private static final ErrorResponse INVALID_DATE =
      new ErrorResponse("data deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199");
  private static final ErrorResponse OUT_OF_RANGE = new ErrorResponse("patrimônio fora do limite");

  private final NetWorthService netWorth;
  private final Clock clock;

  NetWorthController(NetWorthService netWorth, Clock clock) {
    this.netWorth = netWorth;
    this.clock = clock;
  }

  @Operation(
      operationId = "getPosicoesCripto",
      summary = "Lista as posições de cripto até uma data",
      description =
          "Soma dos movimentos até a data nas contas ativas, com a última cotação até o fim do"
              + " dia em America/Sao_Paulo. Reais pela cotação USDC/BRL (ou PTAX sem ela), em `dolar`."
              + " Posição zerada não aparece. contas traz o valor de cada conta cripto. Posição com valor fora do"
              + " inteiro seguro do JSON vem sem valor e com motivoSemValor. Sem data, vale hoje.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Posições",
      content = @Content(schema = @Schema(implementation = PositionListResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "data fora do formato",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/posicoes-cripto", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> positions(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(type = "string", format = "date") @RequestParam(name = "data", required = false)
          String date) {
    Optional<LocalDate> day = dateOrToday(date);
    if (day.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_DATE);
    }
    NetWorthService.Positions positions = netWorth.positions(user.id(), day.get());
    CryptoQueries.Dollar dollar = positions.dollar();
    BigDecimal rate = dollar == null ? null : dollar.value();
    return ResponseEntity.ok(
        new PositionListResponse(
            day.get(),
            dollar == null
                ? null
                : new PositionListResponse.Dollar(
                    Decimals.format(dollar.value()),
                    dollar.date(),
                    dollar.moment(),
                    dollar.source()),
            positions.positions().stream()
                .map(position -> PositionResponse.from(position, rate))
                .toList(),
            positions.accounts().stream()
                .map(
                    account ->
                        new PositionListResponse.Account(
                            account.accountId(),
                            PositionResponse.cents(account.valueBrl()),
                            PositionResponse.cents(account.valueUsd())))
                .toList()));
  }

  @Operation(
      operationId = "getPatrimonio",
      summary = "Patrimônio em reais numa data",
      description =
          "Saldo até a data das contas ativas que não são cripto mais o valor das posições com"
              + " cotação. Posições sem cotação ficam em semCotacao, fora do total. Sem data,"
              + " vale hoje em America/Sao_Paulo.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Patrimônio",
      content = @Content(schema = @Schema(implementation = NetWorthResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "data fora do formato ou \"patrimônio fora do limite\"",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/patrimonio", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> netWorth(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(type = "string", format = "date") @RequestParam(name = "data", required = false)
          String date) {
    Optional<LocalDate> day = dateOrToday(date);
    if (day.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_DATE);
    }
    return netWorth
        .netWorth(user.id(), day.get())
        .<ResponseEntity<?>>map(found -> ResponseEntity.ok(NetWorthResponse.from(day.get(), found)))
        .orElseGet(() -> ResponseEntity.badRequest().body(OUT_OF_RANGE));
  }

  // Lida como texto: o conversor padrão de LocalDate aceita formatos que o contrato não prevê.
  private Optional<LocalDate> dateOrToday(String date) {
    if (date == null) {
      return Optional.of(clock.instant().atZone(NetWorthService.ZONE).toLocalDate());
    }
    return IsoDates.parse(date);
  }
}
