package com.felipe.ballast.identity;

import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Fechado, o bean nem existe: qualquer método em /auth/cadastro cai no mesmo caminho de uma
// rota desconhecida, sem o 405 e o Allow que denunciariam que o cadastro existe.
@Tag(name = "auth")
@RestController
@ConditionalOnProperty(prefix = "ballast", name = "registration-open", havingValue = "true")
class RegistrationController {

  private static final Logger log = LoggerFactory.getLogger(RegistrationController.class);

  private final IdentityService identity;

  RegistrationController(IdentityService identity) {
    this.identity = identity;
  }

  @Operation(
      operationId = "postAuthCadastro",
      summary = "Cria um usuário",
      description =
          "A rota só existe quando REGISTRO_ABERTO=true. Fechado, ela responde exatamente como"
              + " uma rota inexistente (401 sem sessão, 404 com sessão), para não revelar que existe.")
  @ApiResponse(
      responseCode = "201",
      description = "Usuário criado",
      content = @Content(schema = @Schema(implementation = UserResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "E-mail já cadastrado",
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
      path = "/auth/cadastro",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
    Optional<UserProfile> created =
        identity.register(request.email(), request.password(), request.name());
    if (created.isEmpty()) {
      log.warn(
          "cadastro recusado: e-mail já cadastrado email={} ip={}",
          MaskedEmail.of(request.email()),
          ClientAddress.of(http));
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(new ErrorResponse("e-mail já cadastrado"));
    }
    log.info("cadastro criado usuario_id={} ip={}", created.get().id(), ClientAddress.of(http));
    return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(created.get()));
  }
}
