package com.felipe.ballast.cards;

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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "cartao")
@RestController
class InvoiceController {

  // Mesmo corpo para cartão de outro usuário, conta inexistente, id que não é UUID e conta que não
  // é cartão: diferenciar diria ao cliente quais ids existem.
  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");
  private static final ErrorResponse OUT_OF_RANGE = new ErrorResponse("fatura fora do limite");
  private static final ErrorResponse INVALID_FROM =
      new ErrorResponse("de deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199");
  private static final ErrorResponse INVALID_TO =
      new ErrorResponse("ate deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199");
  private static final ErrorResponse FROM_AFTER_TO =
      new ErrorResponse("de deve ser anterior ou igual a ate");
  private static final ErrorResponse RANGE_TOO_LONG =
      new ErrorResponse("o intervalo entre de e ate deve ter no máximo 366 dias");

  // Conta os dois extremos, como em GET /lancamentos.
  private static final long MAX_DAYS = 366;

  // "Hoje" é o dia do usuário, não o do servidor em UTC.
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  private final InvoiceService invoices;
  private final Clock clock;

  InvoiceController(InvoiceService invoices, Clock clock) {
    this.invoices = invoices;
    this.clock = clock;
  }

  @Operation(
      operationId = "getContasByIdFaturas",
      summary = "Lista as faturas de um cartão por vencimento",
      description =
          "Todas as faturas com vencimento entre de e ate (pontas incluídas, no máximo 366 dias),"
              + " em ordem de vencimento, inclusive as sem compra. pago soma as transferências"
              + " realizadas para o cartão depois do fechamento da fatura e até o fechamento da"
              + " seguinte, inclusive; pagamento feito antes do fechamento conta na fatura"
              + " anterior. situacao é aberta até o dia do fechamento (inclusive, em"
              + " America/Sao_Paulo), só pela data; depois dele, fechada com restante maior que"
              + " zero e paga com restante menor ou igual a zero.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Faturas do período",
      content = @Content(schema = @Schema(implementation = InvoiceListResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "de ou ate ausente ou fora do formato AAAA-MM-DD (ano de 1900 a 2199), de depois de"
              + " ate, intervalo maior que 366 dias ou \"fatura fora do limite\" (algum valor"
              + " sairia de ±9007199254740991 centavos)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Conta de outro usuário, inexistente, id que não é UUID ou conta que não é cartão; todos"
              + " com o mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível; pode tentar de novo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/contas/{id}/faturas", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Schema(type = "string", format = "date", requiredMode = Schema.RequiredMode.REQUIRED)
          @RequestParam(name = "de", required = false)
          String from,
      @Schema(type = "string", format = "date", requiredMode = Schema.RequiredMode.REQUIRED)
          @RequestParam(name = "ate", required = false)
          String to) {
    Optional<UUID> accountId = CanonicalUuid.parse(id);
    if (accountId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
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
    LocalDate today = clock.instant().atZone(ZONE).toLocalDate();
    InvoiceService.Result result =
        invoices.list(user.id(), accountId.get(), fromDate.get(), toDate.get(), today);
    if (result instanceof InvoiceService.Result.Found found) {
      List<InvoiceResponse> body =
          found.invoices().stream().map(InvoiceController::toResponse).toList();
      return ResponseEntity.ok(new InvoiceListResponse(accountId.get(), body));
    }
    return switch ((InvoiceService.Result.Refused) result) {
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case OUT_OF_RANGE -> ResponseEntity.badRequest().body(OUT_OF_RANGE);
    };
  }

  private static InvoiceResponse toResponse(InvoiceService.Invoice invoice) {
    return new InvoiceResponse(
        invoice.due(),
        invoice.closing(),
        cents(invoice.total()),
        cents(invoice.paid()),
        cents(invoice.remaining()),
        invoice.situation().value());
  }

  // A faixa já foi conferida no serviço; longValueExact falha alto se algum valor escapar.
  private static long cents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
