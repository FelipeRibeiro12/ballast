package com.felipe.ballast.imports;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.CanonicalUuid;
import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Envio de PDF de fatura (ADR-073). Melhor esforço: o resultado é sempre uma importação pendente,
// e a revisão, a confirmação e o descarte são as rotas de /importacoes. O dono vem da sessão; conta
// de outro usuário responde 404. O log leva só ids e contagens: texto da fatura nunca entra.
@Tag(name = "importacoes")
@RestController
class PdfImportController {

  private static final Logger log = LoggerFactory.getLogger(PdfImportController.class);

  private static final ErrorResponse INVALID_PARAMETERS =
      new ErrorResponse("conta_id é obrigatório e deve ser um UUID");

  private final ImportService service;

  PdfImportController(ImportService service) {
    this.service = service;
  }

  @Operation(
      operationId = "postImportacoesPdf",
      summary =
          "Envia o PDF de uma fatura de cartão e devolve a proposta por linha (melhor esforço)",
      description =
          "O corpo é o PDF cru (application/octet-stream), de até 5 MiB e 30 páginas, com texto"
              + " (sem OCR) e sem senha. A leitura é por heurística e pode errar ou não achar"
              + " linhas: o que não foi reconhecido entra como linha inválida, com o texto cru, e"
              + " só aceita ignorar. Nada vira lançamento antes da confirmação em"
              + " /importacoes/{id}/confirmacao. A conta precisa ser de cartão de crédito. O"
              + " arquivo não é guardado.",
      security = @SecurityRequirement(name = "cookieAuth"),
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                      schema = @Schema(type = "string", format = "binary"))))
  @ApiResponse(
      responseCode = "201",
      description = "Importação pendente criada",
      content = @Content(schema = @Schema(implementation = ImportDetailResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "conta_id ausente, repetido ou que não é UUID, parâmetro não declarado, conta que não é"
              + " de cartão de crédito ou conta arquivada",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Conta de outro usuário ou inexistente; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description =
          "Arquivo já enviado (corpo com importacaoId) ou 20 importações pendentes (corpo comum"
              + " de erro)",
      content = @Content(schema = @Schema(implementation = DuplicateImportResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "Arquivo maior que 5 MiB",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "415",
      description = "Content-Type diferente de application/octet-stream",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "422",
      description =
          "Arquivo vazio, que não é PDF, ilegível, com senha, sem texto, com mais de 30 páginas,"
              + " lento demais ou sem nenhuma transação reconhecida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/importacoes/pdf",
      consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> upload(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(type = "string", format = "uuid") @RequestParam(name = "conta_id", required = false)
          String accountId,
      @RequestBody(required = false) byte[] content,
      HttpServletRequest request) {
    Optional<UUID> account = CanonicalUuid.parse(accountId);
    boolean onlyAccount =
        request.getParameterMap().entrySet().stream()
            .allMatch(e -> "conta_id".equals(e.getKey()) && e.getValue().length == 1);
    if (account.isEmpty() || !onlyAccount) {
      return ResponseEntity.badRequest().body(INVALID_PARAMETERS);
    }
    ImportService.Result<ImportService.Detail> result =
        service.uploadPdf(user.id(), account.get(), content);
    if (result instanceof ImportService.Result.Ok<ImportService.Detail> ok) {
      ImportDetailResponse body =
          ImportDetailResponse.from(ok.value().header(), ok.value().lines());
      log.info(
          "importação de PDF criada usuario_id={} importacao_id={} linhas={}",
          user.id(),
          body.id(),
          body.totalLinhas());
      return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
    return ImportController.failure((ImportService.Result.Failed<?>) result);
  }
}
