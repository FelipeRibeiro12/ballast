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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "auth")
@RestController
class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  // Mensagem única para e-mail inexistente e senha errada: diferenciar entregaria quais
  // e-mails têm conta.
  private static final ErrorResponse BAD_CREDENTIALS = new ErrorResponse("credenciais inválidas");

  // Igual para e-mail com e sem conta, pelo mesmo motivo.
  private static final ErrorResponse TOO_MANY_ATTEMPTS =
      new ErrorResponse("muitas tentativas, tente mais tarde");

  private static final ErrorResponse NOT_AUTHENTICATED = new ErrorResponse("não autenticado");

  private final IdentityService identity;
  private final SessionCookies cookies;

  AuthController(IdentityService identity, SessionCookies cookies) {
    this.identity = identity;
    this.cookies = cookies;
  }

  @Operation(
      operationId = "postAuthLogin",
      summary = "Abre uma sessão",
      description =
          "A sessão vai no cookie ballast_sessao (HttpOnly, Secure, SameSite=Lax), válido por 7"
              + " dias absolutos, sem renovação. E-mail inexistente e senha errada dão a mesma"
              + " resposta.")
  @ApiResponse(
      responseCode = "200",
      description = "Sessão aberta",
      headers =
          @Header(
              name = "Set-Cookie",
              required = true,
              description =
                  "ballast_sessao=<token>; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=604800",
              schema = @Schema(type = "string")),
      content = @Content(schema = @Schema(implementation = UserResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Credenciais inválidas",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "Corpo maior que 4 KiB, com ou sem Content-Length",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "429",
      description =
          "Atraso progressivo em vigor para esse e-mail; resposta idêntica para e-mail com e sem"
              + " conta",
      headers =
          @Header(
              name = "Retry-After",
              required = true,
              description =
                  "Segundos inteiros, arredondados para cima, até a próxima tentativa ser"
                      + " verificada",
              schema = @Schema(type = "integer", minimum = "1")),
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "415",
      description = "Content-Type diferente de application/json",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "500",
      description = "Falha interna",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/auth/login",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    String ip = ClientAddress.of(http);
    return switch (identity.authenticate(request.email(), request.password())) {
      case IdentityService.LoginResult.Delayed delayed -> {
        // Arredonda para cima: dizer 0 faria o cliente tentar de novo ainda dentro do atraso.
        long seconds =
            Math.max(1, (delayed.retryAfter().toNanos() + 999_999_999L) / 1_000_000_000L);
        log.warn(
            "login recusado: muitas tentativas email={} ip={} falhas={} retry_after={}",
            MaskedEmail.of(request.email()),
            ip,
            delayed.failures(),
            seconds);
        yield ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
            .body(TOO_MANY_ATTEMPTS);
      }
      case IdentityService.LoginResult.BadCredentials bad -> {
        log.warn(
            "login falhou email={} ip={} falhas={}",
            MaskedEmail.of(request.email()),
            ip,
            bad.failures());
        yield ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(BAD_CREDENTIALS);
      }
      case IdentityService.LoginResult.Success success -> {
        UserProfile user = success.user();
        IdentityService.NewSession session = identity.startSession(user.id());
        log.info("login ok usuario_id={} ip={}", user.id(), ip);
        yield ResponseEntity.ok()
            .header(
                HttpHeaders.SET_COOKIE,
                cookies.open(session.token(), IdentityService.SESSION_LIFETIME))
            .body(UserResponse.from(user));
      }
    };
  }

  @Operation(
      operationId = "getAuthEu",
      summary = "Devolve o usuário da sessão",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Usuário da sessão",
      content = @Content(schema = @Schema(implementation = UserResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem cookie, ou token desconhecido, expirado ou revogado; todos iguais",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "500",
      description = "Falha interna",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/auth/eu", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> me(@AuthenticationPrincipal AuthenticatedUser user) {
    // O id vem da sessão, nunca da requisição. Sem perfil (usuário apagado depois do login), a
    // sessão não vale mais.
    return identity
        .findProfile(user.id())
        .<ResponseEntity<?>>map(profile -> ResponseEntity.ok(UserResponse.from(profile)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(NOT_AUTHENTICATED));
  }

  // Rota pública de propósito: logout sem sessão válida também é 204,
  // e a revogação acontece no servidor, não só no cookie. Não lê corpo.
  @Operation(
      operationId = "postAuthLogout",
      summary = "Encerra a sessão",
      description =
          "Marca a sessão como revogada no servidor e expira o cookie. Sem sessão válida também"
              + " responde 204. Não lê corpo.")
  @ApiResponse(
      responseCode = "204",
      description = "Sessão encerrada, ou não havia sessão",
      headers =
          @Header(
              name = "Set-Cookie",
              required = true,
              description = "ballast_sessao=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0",
              schema = @Schema(type = "string")))
  @ApiResponse(
      responseCode = "413",
      description = "Content-Length acima de 4 KiB; o corpo nunca é lido",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "500",
      description = "Falha interna; a sessão pode não ter sido revogada",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/auth/logout")
  ResponseEntity<Void> logout(HttpServletRequest http) {
    Optional<UUID> revokedOwner = SessionCookies.tokenOf(http).flatMap(identity::revokeSession);
    log.info(
        "logout usuario_id={} sessao_revogada={} ip={}",
        revokedOwner.map(UUID::toString).orElse("-"),
        revokedOwner.isPresent(),
        ClientAddress.of(http));
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.expire()).build();
  }
}
