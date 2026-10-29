package com.felipe.ballast.imports;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.CanonicalUuid;
import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão. Mapeamento de outro usuário responde 404 com o mesmo corpo do
// inexistente. O log leva só ids: o nome do banco do usuário não entra nele.
@Tag(name = "importacoes")
@RestController
class CsvMappingController {

  private static final Logger log = LoggerFactory.getLogger(CsvMappingController.class);

  static final ErrorResponse NOT_FOUND = new ErrorResponse("mapeamento não encontrado");
  private static final ErrorResponse NAME_TAKEN =
      new ErrorResponse("já existe um mapeamento com esse nome");
  private static final ErrorResponse DISTINCT_COLUMNS =
      new ErrorResponse("as colunas de data, valor, descrição e id devem ser diferentes");

  private final CsvMappingService service;

  CsvMappingController(CsvMappingService service) {
    this.service = service;
  }

  @Operation(
      operationId = "getMapeamentosCsv",
      summary = "Lista os mapeamentos de CSV do usuário",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Mapeamentos, do mais antigo para o mais novo",
      content =
          @Content(
              array = @ArraySchema(schema = @Schema(implementation = CsvMappingResponse.class))))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/mapeamentos-csv", produces = MediaType.APPLICATION_JSON_VALUE)
  List<CsvMappingResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
    return service.list(user.id()).stream().map(CsvMappingResponse::from).toList();
  }

  @Operation(
      operationId = "postMapeamentosCsv",
      summary = "Salva um mapeamento de colunas de CSV",
      description = "Até 20 por usuário. O nome é único por usuário.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Mapeamento criado",
      content = @Content(schema = @Schema(implementation = CsvMappingResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida, inclusive campo não declarado e colunas repetidas",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Nome já usado ou limite de mapeamentos atingido",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/mapeamentos-csv",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CsvMappingRequest request) {
    CsvMapping mapping = request.toMapping();
    if (!mapping.hasDistinctColumns()) {
      return ResponseEntity.badRequest().body(DISTINCT_COLUMNS);
    }
    CsvMappingService.Result result = service.create(user.id(), request.name(), mapping);
    if (result instanceof CsvMappingService.Result.Ok ok) {
      log.info("mapeamento de CSV criado usuario_id={} mapeamento_id={}", user.id(), ok.row().id());
      return ResponseEntity.status(HttpStatus.CREATED).body(CsvMappingResponse.from(ok.row()));
    }
    return failure((CsvMappingService.Result.Failed) result);
  }

  @Operation(
      operationId = "putMapeamentosCsvById",
      summary = "Troca todos os campos do mapeamento",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Mapeamento atualizado",
      content = @Content(schema = @Schema(implementation = CsvMappingResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida, inclusive campo não declarado e colunas repetidas",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Mapeamento de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Nome já usado por outro mapeamento do usuário",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/mapeamentos-csv/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody CsvMappingRequest request) {
    Optional<UUID> mappingId = CanonicalUuid.parse(id);
    if (mappingId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    CsvMapping mapping = request.toMapping();
    if (!mapping.hasDistinctColumns()) {
      return ResponseEntity.badRequest().body(DISTINCT_COLUMNS);
    }
    CsvMappingService.Result result =
        service.update(user.id(), mappingId.get(), request.name(), mapping);
    if (result instanceof CsvMappingService.Result.Ok ok) {
      return ResponseEntity.ok(CsvMappingResponse.from(ok.row()));
    }
    return failure((CsvMappingService.Result.Failed) result);
  }

  @Operation(
      operationId = "deleteMapeamentosCsvById",
      summary = "Apaga o mapeamento",
      description = "As importações já criadas com ele não mudam.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Mapeamento apagado")
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Mapeamento de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/mapeamentos-csv/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> mappingId = CanonicalUuid.parse(id);
    if (mappingId.isEmpty() || !service.delete(user.id(), mappingId.get())) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return ResponseEntity.noContent().build();
  }

  private static ResponseEntity<?> failure(CsvMappingService.Result.Failed failed) {
    return switch (failed.failure()) {
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case NAME_TAKEN -> ResponseEntity.status(HttpStatus.CONFLICT).body(NAME_TAKEN);
    };
  }
}
