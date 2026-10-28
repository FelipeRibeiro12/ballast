package com.felipe.ballast.projection;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "saldo-projetado")
@RestController
class SpendableController {

  private static final ErrorResponse OUT_OF_RANGE = new ErrorResponse("projeção fora do limite");

  private final SpendableService spendable;

  SpendableController(SpendableService spendable) {
    this.spendable = spendable;
  }

  @Operation(
      operationId = "getPossoGastar",
      summary = "Quanto dá para gastar até o fim da janela",
      description =
          "O menor saldo projetado (ADR-029) das contas que não são de benefício, de hoje até o dia"
              + " que o usuário escolheu, ou até a véspera do próximo pagamento da fatura âncora,"
              + " ou até o fim do mês sem cartão. Compra de terceiro fica fora e o rendimento dos"
              + " cofrinhos entra. Cada benefício vem à parte (ADR-063).",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Quanto dá para gastar",
      content = @Content(schema = @Schema(implementation = SpendableResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "\"projeção fora do limite\"",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/posso-gastar", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> compute(@AuthenticationPrincipal AuthenticatedUser user) {
    return spendable
        .compute(user.id())
        .<ResponseEntity<?>>map(found -> ResponseEntity.ok(SpendableResponse.from(found)))
        .orElseGet(() -> ResponseEntity.badRequest().body(OUT_OF_RANGE));
  }

  @Operation(
      operationId = "putPossoGastarConfiguracao",
      summary = "Escolhe até que dia do mês o Posso gastar conta",
      description =
          "diaLimite de 1 a 31 fixa o dia; nulo volta ao ciclo da fatura. Não altera lançamento"
              + " nenhum (ADR-063).",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Preferência gravada")
  @ApiResponse(
      responseCode = "400",
      description = "diaLimite fora de 1 a 31",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/posso-gastar/configuracao",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Void> configure(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody SpendableConfigRequest request) {
    spendable.configure(user.id(), request.limitDay());
    return ResponseEntity.noContent().build();
  }
}
