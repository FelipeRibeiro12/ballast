package com.felipe.ballast.identity;

import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    Optional<UserProfile> user = identity.authenticate(request.email(), request.password());
    if (user.isEmpty()) {
      log.warn(
          "login falhou email={} ip={}", MaskedEmail.of(request.email()), ClientAddress.of(http));
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(BAD_CREDENTIALS);
    }
    IdentityService.NewSession session = identity.startSession(user.get().id());
    log.info("login ok usuario_id={} ip={}", user.get().id(), ClientAddress.of(http));
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE, cookies.open(session.token(), IdentityService.SESSION_LIFETIME))
        .body(UserResponse.from(user.get()));
  }
}
