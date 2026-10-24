package com.felipe.ballast.identity;

import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// ADR-050. As rotas /auth/mfa/* exigem sessão; /auth/login/mfa é pública e lê o cookie do
// desafio que o login deixou.
@Tag(name = "auth")
@RestController
class MfaController {

  private static final Logger log = LoggerFactory.getLogger(MfaController.class);

  private final MfaService mfa;
  private final IdentityService identity;
  private final SessionCookies cookies;

  MfaController(MfaService mfa, IdentityService identity, SessionCookies cookies) {
    this.mfa = mfa;
    this.identity = identity;
    this.cookies = cookies;
  }

  @Operation(
      operationId = "getAuthMfa",
      summary = "Diz se o MFA está ligado",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Situação do MFA",
      content = @Content(schema = @Schema(implementation = MfaResponses.Status.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/auth/mfa", produces = MediaType.APPLICATION_JSON_VALUE)
  MfaResponses.Status status(@AuthenticationPrincipal AuthenticatedUser user) {
    MfaService.Status status = mfa.status(user.id());
    return new MfaResponses.Status(status.active(), status.remainingRecoveryCodes());
  }

  @Operation(
      operationId = "postAuthMfaConfigurar",
      summary = "Gera o segredo do autenticador",
      description =
          "Pede a senha. O MFA só liga no POST /auth/mfa/ativar; chamar de novo antes disso troca"
              + " o segredo.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Segredo novo, ainda não ativo",
      content = @Content(schema = @Schema(implementation = MfaResponses.Setup.class)))
  @ApiResponse(
      responseCode = "400",
      description = "senha incorreta",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "mfa já ativo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "mfa indisponível: a API está sem MFA_CHAVE",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/auth/mfa/configurar",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  MfaResponses.Setup configure(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody MfaRequests.Password request,
      HttpServletRequest http) {
    MfaService.Setup setup = mfa.configure(user.id(), request.password());
    log.info("mfa configurado usuario_id={} ip={}", user.id(), ClientAddress.of(http));
    return new MfaResponses.Setup(setup.secret(), setup.uri());
  }

  @Operation(
      operationId = "postAuthMfaAtivar",
      summary = "Liga o MFA com o primeiro código do autenticador",
      description =
          "Responde com os códigos de recuperação, que só aparecem aqui. Ligar revoga todas as"
              + " outras sessões do usuário; a atual continua.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "MFA ligado; os códigos de recuperação não aparecem de novo",
      content = @Content(schema = @Schema(implementation = MfaResponses.Activation.class)))
  @ApiResponse(
      responseCode = "400",
      description = "código inválido",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "configure o mfa antes, ou mfa já ativo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "429",
      description = "Atraso progressivo por usuário depois de 3 códigos errados",
      headers = @Header(name = "Retry-After", required = true, schema = @Schema(type = "integer")),
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/auth/mfa/ativar",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  MfaResponses.Activation activate(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody MfaRequests.Code request,
      HttpServletRequest http) {
    // A rota exige sessão, então o cookie existe; a sessão dele é a única que sobrevive.
    byte[] currentSession =
        SessionTokens.hash(
            cookies
                .tokenOf(http)
                .orElseThrow(() -> MfaError.of(HttpStatus.UNAUTHORIZED, "não autenticado")));
    List<String> codes = mfa.activate(user.id(), request.code(), currentSession);
    log.info("mfa ativado usuario_id={} ip={}", user.id(), ClientAddress.of(http));
    return new MfaResponses.Activation(codes);
  }

  @Operation(
      operationId = "postAuthMfaCodigosDeRecuperacao",
      summary = "Gera dez códigos de recuperação novos e invalida os antigos",
      description =
          "Pede o código do autenticador de agora; código de recuperação não vale aqui. Os códigos"
              + " novos só aparecem nesta resposta. Conta no mesmo atraso por usuário do restante do"
              + " MFA.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Códigos novos; os antigos deixam de valer",
      content = @Content(schema = @Schema(implementation = MfaResponses.Activation.class)))
  @ApiResponse(
      responseCode = "400",
      description = "código inválido",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "mfa não está ativo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "429",
      description = "Atraso por usuário em vigor, ou limite por IP",
      headers = @Header(name = "Retry-After", required = true, schema = @Schema(type = "integer")),
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/auth/mfa/codigos-de-recuperacao",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  MfaResponses.Activation regenerateRecoveryCodes(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody MfaRequests.Code request,
      HttpServletRequest http) {
    List<String> codes = mfa.regenerateRecoveryCodes(user.id(), request.code());
    log.warn(
        "mfa: códigos de recuperação renovados usuario_id={} ip={}",
        user.id(),
        ClientAddress.of(http));
    return new MfaResponses.Activation(codes);
  }

  @Operation(
      operationId = "postAuthMfaDesativar",
      summary = "Desliga o MFA",
      description = "Pede a senha e um código, do autenticador ou de recuperação.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "MFA desligado")
  @ApiResponse(
      responseCode = "400",
      description = "senha incorreta, ou código inválido",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "mfa não está ativo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "429",
      description = "Atraso progressivo por usuário depois de 3 códigos errados",
      headers = @Header(name = "Retry-After", required = true, schema = @Schema(type = "integer")),
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(path = "/auth/mfa/desativar", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Void> disable(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody MfaRequests.Disable request,
      HttpServletRequest http) {
    mfa.disable(user.id(), request.password(), request.code());
    log.info("mfa desativado usuario_id={} ip={}", user.id(), ClientAddress.of(http));
    return ResponseEntity.noContent().build();
  }

  @Operation(
      operationId = "postAuthLoginMfa",
      summary = "Responde o desafio do login com o código",
      description =
          "Lê o cookie __Host-ballast_mfa que o POST /auth/login deixou (5 minutos). Aceita o código do"
              + " autenticador ou um de recuperação. Certo, abre a sessão como o login e expira o"
              + " cookie do desafio.")
  @ApiResponse(
      responseCode = "200",
      description = "Sessão aberta",
      headers =
          @Header(
              name = "Set-Cookie",
              required = true,
              description = "__Host-ballast_sessao=<token>, e __Host-ballast_mfa expirado",
              schema = @Schema(type = "string")),
      content = @Content(schema = @Schema(implementation = UserResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "código inválido (corpo fora do formato)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "desafio expirado ou inválido, ou código inválido",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "429",
      description = "Atraso progressivo por usuário depois de 3 códigos errados",
      headers = @Header(name = "Retry-After", required = true, schema = @Schema(type = "integer")),
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/auth/login/mfa",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> answer(@Valid @RequestBody MfaRequests.Code request, HttpServletRequest http) {
    String token =
        cookies
            .challengeOf(http)
            .orElseThrow(
                () -> MfaError.of(HttpStatus.UNAUTHORIZED, "desafio expirado ou inválido"));
    UUID userId;
    try {
      userId = mfa.answerChallenge(token, request.code());
    } catch (MfaError e) {
      log.warn("login mfa recusado motivo={} ip={}", e.getMessage(), ClientAddress.of(http));
      throw e;
    }
    UserProfile profile =
        identity
            .findProfile(userId)
            .orElseThrow(
                () -> MfaError.of(HttpStatus.UNAUTHORIZED, "desafio expirado ou inválido"));
    IdentityService.NewSession session = identity.startSession(userId);
    log.info("login ok com mfa usuario_id={} ip={}", userId, ClientAddress.of(http));
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE, cookies.open(session.token(), IdentityService.SESSION_LIFETIME))
        .header(HttpHeaders.SET_COOKIE, cookies.expireChallenge())
        .body(UserResponse.from(profile));
  }

  @ExceptionHandler(MfaError.class)
  ResponseEntity<ErrorResponse> handle(MfaError error) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(error.status());
    if (error.retryAfter() != null) {
      // Para cima: dizer 0 faria o cliente tentar de novo ainda dentro do atraso.
      long seconds = Math.max(1, (error.retryAfter().toMillis() + 999) / 1000);
      response.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
    }
    return response
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ErrorResponse(error.getMessage()));
  }
}
