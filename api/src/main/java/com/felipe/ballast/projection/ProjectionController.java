package com.felipe.ballast.projection;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.CanonicalUuid;
import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca da query.
@Tag(name = "saldo-projetado")
@RestController
class ProjectionController {

  private static final ErrorResponse INVALID_DAYS =
      new ErrorResponse("dias deve ser um inteiro de 1 a 366");
  private static final ErrorResponse INVALID_ACCOUNT_FILTER =
      new ErrorResponse("contaId deve ser um UUID");
  // Mesmo corpo para conta de outro usuário e inexistente: diferenciar diria quais ids existem.
  private static final ErrorResponse ACCOUNT_NOT_FOUND = new ErrorResponse("conta não encontrada");
  private static final ErrorResponse OUT_OF_RANGE = new ErrorResponse("projeção fora do limite");

  private static final int DEFAULT_DAYS = 90;
  private static final int MAX_DAYS = 366;

  // Só dígitos ASCII, sem sinal, sem zero à esquerda e no máximo três casas: Integer.parseInt
  // aceitaria "+5" e dígitos de outros alfabetos.
  private static final Pattern DAYS_PATTERN = Pattern.compile("[1-9][0-9]{0,2}");

  // "Hoje" é o dia do usuário, não o do servidor em UTC (mesmo fuso do resto do app).
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  private final ProjectionService projections;
  private final Clock clock;

  ProjectionController(ProjectionService projections, Clock clock) {
    this.projections = projections;
    this.clock = clock;
  }

  @Operation(
      operationId = "getSaldoProjetado",
      summary = "Série diária do saldo projetado por dataCaixa",
      description =
          "Um ponto por dia, de hoje (America/Sao_Paulo) até hoje + dias - 1. O saldo do dia é o"
              + " saldo inicial das contas mais tudo que é realizado ou previsto com dataCaixa"
              + " até o dia; cancelado nunca entra, e a prevista vencida entra pela própria"
              + " data. Sem contaId soma as contas ativas (transferência entre duas delas se"
              + " anula; de ativa para arquivada sai do total). Com contaId vale só a conta, também"
              + " arquivada, e a transferência conta pelo sinal da perna dela. entradas e saídas"
              + " de um dia são só os lançamentos com dataCaixa naquele dia; a perna de"
              + " transferência só conta quando a outra perna está fora do filtro.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Série diária",
      content = @Content(schema = @Schema(implementation = ProjectionResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "dias que não é inteiro de 1 a 366 (ou repetido), contaId que não é UUID (ou repetido)"
              + " ou \"projeção fora do limite\" (algum valor sairia de ±9007199254740991"
              + " centavos)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "contaId de outro usuário ou inexistente, com o mesmo corpo \"conta não encontrada\"",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível; pode tentar de novo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/saldo-projetado", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> project(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(
              type = "integer",
              minimum = "1",
              maximum = "366",
              defaultValue = "90",
              description = "Quantos dias a série tem, contando hoje")
          @RequestParam(name = "dias", required = false)
          String daysParam,
      @Schema(type = "string", format = "uuid") @RequestParam(name = "contaId", required = false)
          String accountParam) {
    // Lidos como texto: o conversor padrão de inteiro aceita "+5", e parâmetro repetido chega
    // como "5,5", que a validação recusa.
    int days = DEFAULT_DAYS;
    if (daysParam != null) {
      if (!DAYS_PATTERN.matcher(daysParam).matches() || Integer.parseInt(daysParam) > MAX_DAYS) {
        return ResponseEntity.badRequest().body(INVALID_DAYS);
      }
      days = Integer.parseInt(daysParam);
    }
    UUID account = null;
    if (accountParam != null) {
      Optional<UUID> parsed = CanonicalUuid.parse(accountParam);
      if (parsed.isEmpty()) {
        return ResponseEntity.badRequest().body(INVALID_ACCOUNT_FILTER);
      }
      account = parsed.get();
    }
    LocalDate today = clock.instant().atZone(ZONE).toLocalDate();
    ProjectionService.Result result = projections.project(user.id(), account, today, days);
    if (result instanceof ProjectionService.Result.Found found) {
      return ResponseEntity.ok(ProjectionResponse.from(found.projection()));
    }
    return switch ((ProjectionService.Result.Refused) result) {
      case ACCOUNT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ACCOUNT_NOT_FOUND);
      case OUT_OF_RANGE -> ResponseEntity.badRequest().body(OUT_OF_RANGE);
    };
  }
}
