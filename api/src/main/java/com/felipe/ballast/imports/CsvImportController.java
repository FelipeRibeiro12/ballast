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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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

// Envio e prévia de CSV (ADR-070). A revisão, a confirmação e o descarte são as rotas de
// /importacoes, que já servem aos dois formatos. O dono vem sempre da sessão; conta e mapeamento
// de outro usuário respondem 404. O log leva só ids e contagens: célula e descrição nunca entram.
@Tag(name = "importacoes")
@RestController
class CsvImportController {

  private static final Logger log = LoggerFactory.getLogger(CsvImportController.class);

  private static final ErrorResponse INVALID_UPLOAD_PARAMETERS =
      new ErrorResponse("conta_id e mapeamento_id são obrigatórios e devem ser UUIDs");
  private static final ErrorResponse INVALID_PREVIEW_PARAMETERS =
      new ErrorResponse(
          "parâmetros do mapeamento inválidos: informe separador, formato_data, decimal, sinal,"
              + " tem_cabecalho, coluna_data, coluna_valor e coluna_descricao (e coluna_id, se"
              + " houver), com colunas diferentes entre si");

  private static final Set<String> UPLOAD_PARAMETERS = Set.of("conta_id", "mapeamento_id");
  private static final Set<String> PREVIEW_PARAMETERS =
      Set.of(
          "separador",
          "formato_data",
          "decimal",
          "sinal",
          "tem_cabecalho",
          "coluna_data",
          "coluna_valor",
          "coluna_descricao",
          "coluna_id");
  private static final Pattern COLUMN = Pattern.compile("\\d{1,2}");

  private final ImportService service;

  CsvImportController(ImportService service) {
    this.service = service;
  }

  @Operation(
      operationId = "postImportacoesCsv",
      summary = "Envia um extrato CSV e devolve a proposta por linha",
      description =
          "Igual ao envio de OFX, lendo o arquivo pelo mapeamento salvo. O corpo é o arquivo cru"
              + " (application/octet-stream), de até 1 MiB, 2000 linhas de dados e 50 colunas por"
              + " linha. Linha com data ou valor ilegível entra como inválida e fica de fora da"
              + " confirmação. A revisão e a confirmação são as de /importacoes/{id}.",
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
          "conta_id ou mapeamento_id ausente ou que não é UUID, parâmetro não declarado, \"conta"
              + " arquivada\" ou \"conta cripto ou de investimento não recebe lançamento\"",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Conta ou mapeamento de outro usuário ou inexistente; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description =
          "Arquivo já enviado (corpo com importacaoId) ou 20 importações pendentes (corpo comum"
              + " de erro)",
      content = @Content(schema = @Schema(implementation = DuplicateImportResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "Arquivo maior que 1 MiB",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "415",
      description = "Content-Type diferente de application/octet-stream",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "422",
      description =
          "Arquivo vazio, com NUL, com aspas sem fechar, sem linhas de dados, com mais de 2000"
              + " linhas ou mais de 50 colunas",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/importacoes/csv",
      consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> upload(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(type = "string", format = "uuid") @RequestParam(name = "conta_id", required = false)
          String accountId,
      @Schema(type = "string", format = "uuid")
          @RequestParam(name = "mapeamento_id", required = false)
          String mappingId,
      @RequestBody(required = false) byte[] content,
      HttpServletRequest request) {
    Optional<UUID> account = CanonicalUuid.parse(accountId);
    Optional<UUID> mapping = CanonicalUuid.parse(mappingId);
    if (account.isEmpty()
        || mapping.isEmpty()
        || !onlyDeclared(request.getParameterMap(), UPLOAD_PARAMETERS)) {
      return ResponseEntity.badRequest().body(INVALID_UPLOAD_PARAMETERS);
    }
    ImportService.Result<ImportService.Detail> result =
        service.uploadCsv(user.id(), account.get(), mapping.get(), content);
    if (result instanceof ImportService.Result.Ok<ImportService.Detail> ok) {
      ImportDetailResponse body =
          ImportDetailResponse.from(ok.value().header(), ok.value().lines());
      log.info(
          "importação de CSV criada usuario_id={} importacao_id={} linhas={}",
          user.id(),
          body.id(),
          body.totalLinhas());
      return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
    return ImportController.failure((ImportService.Result.Failed<?>) result);
  }

  @Operation(
      operationId = "postImportacoesCsvPrevia",
      summary = "Mostra como um CSV seria lido, sem gravar nada",
      description =
          "Recebe o mapeamento na query e o arquivo cru no corpo (mesmos tetos do envio). Devolve"
              + " as 10 primeiras linhas, com as células e a interpretação de cada uma, o total de"
              + " linhas e o número de colunas da primeira linha. Não cria importação, não grava"
              + " hash e não usa vaga das 20 pendentes. Mesmo limite de requisições do envio.",
      security = @SecurityRequirement(name = "cookieAuth"),
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                      schema = @Schema(type = "string", format = "binary"))))
  @ApiResponse(
      responseCode = "200",
      description = "Prévia das primeiras linhas",
      content = @Content(schema = @Schema(implementation = CsvPreviewResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Parâmetro ausente, inválido, repetido ou não declarado; colunas repetidas",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "Arquivo maior que 1 MiB",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "415",
      description = "Content-Type diferente de application/octet-stream",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "422",
      description = "Mesmos casos do envio",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/importacoes/csv/previa",
      consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> preview(
      @Schema(allowableValues = {"ponto_e_virgula", "virgula", "tab", "barra_vertical"})
          @RequestParam(name = "separador", required = false)
          String separator,
      @Schema(allowableValues = {"dd/MM/yyyy", "yyyy-MM-dd", "MM/dd/yyyy", "dd-MM-yyyy"})
          @RequestParam(name = "formato_data", required = false)
          String dateFormat,
      @Schema(allowableValues = {"virgula", "ponto"})
          @RequestParam(name = "decimal", required = false)
          String decimal,
      @Schema(allowableValues = {"negativo_despesa", "positivo_despesa"})
          @RequestParam(name = "sinal", required = false)
          String sign,
      @Schema(allowableValues = {"true", "false"})
          @RequestParam(name = "tem_cabecalho", required = false)
          String hasHeader,
      @Schema(type = "integer", minimum = "0", maximum = "49")
          @RequestParam(name = "coluna_data", required = false)
          String dateColumn,
      @Schema(type = "integer", minimum = "0", maximum = "49")
          @RequestParam(name = "coluna_valor", required = false)
          String amountColumn,
      @Schema(type = "integer", minimum = "0", maximum = "49")
          @RequestParam(name = "coluna_descricao", required = false)
          String descriptionColumn,
      @Schema(type = "integer", minimum = "0", maximum = "49")
          @RequestParam(name = "coluna_id", required = false)
          String idColumn,
      @RequestBody(required = false) byte[] content,
      HttpServletRequest request) {
    Optional<CsvMapping> mapping =
        onlyDeclared(request.getParameterMap(), PREVIEW_PARAMETERS)
            ? mappingOf(
                separator,
                dateFormat,
                decimal,
                sign,
                hasHeader,
                dateColumn,
                amountColumn,
                descriptionColumn,
                idColumn)
            : Optional.empty();
    if (mapping.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_PREVIEW_PARAMETERS);
    }
    ImportService.Result<CsvFile> result = service.previewCsv(content, mapping.get());
    if (result instanceof ImportService.Result.Ok<CsvFile> ok) {
      return ResponseEntity.ok(CsvPreviewResponse.from(ok.value()));
    }
    return ImportController.failure((ImportService.Result.Failed<?>) result);
  }

  // Parâmetro fora da lista, ou repetido, é recusado em vez de ignorado.
  private static boolean onlyDeclared(Map<String, String[]> parameters, Set<String> declared) {
    return parameters.entrySet().stream()
        .allMatch(entry -> declared.contains(entry.getKey()) && entry.getValue().length == 1);
  }

  // A query chega como texto: o booleano e os índices são lidos à mão, porque o Spring aceita
  // 1, yes e on como true e trunca número.
  private static Optional<CsvMapping> mappingOf(
      String separator,
      String dateFormat,
      String decimal,
      String sign,
      String hasHeader,
      String dateColumn,
      String amountColumn,
      String descriptionColumn,
      String idColumn) {
    Optional<CsvMapping.Separator> parsedSeparator = CsvMapping.Separator.fromValue(separator);
    Optional<CsvMapping.DateFormat> parsedFormat = CsvMapping.DateFormat.fromValue(dateFormat);
    Optional<CsvMapping.Decimal> parsedDecimal = CsvMapping.Decimal.fromValue(decimal);
    Optional<CsvMapping.Sign> parsedSign = CsvMapping.Sign.fromValue(sign);
    boolean headerValid = "true".equals(hasHeader) || "false".equals(hasHeader);
    int date = column(dateColumn);
    int amount = column(amountColumn);
    int description = column(descriptionColumn);
    int id = idColumn == null ? 0 : column(idColumn);
    if (parsedSeparator.isEmpty()
        || parsedFormat.isEmpty()
        || parsedDecimal.isEmpty()
        || parsedSign.isEmpty()
        || !headerValid
        || date < 0
        || amount < 0
        || description < 0
        || id < 0) {
      return Optional.empty();
    }
    CsvMapping mapping =
        new CsvMapping(
            parsedSeparator.get(),
            parsedFormat.get(),
            parsedDecimal.get(),
            parsedSign.get(),
            "true".equals(hasHeader),
            date,
            amount,
            description,
            idColumn == null ? null : id);
    return mapping.hasDistinctColumns() ? Optional.of(mapping) : Optional.empty();
  }

  // -1 para o que não for índice de 0 a 49.
  private static int column(String text) {
    if (text == null || !COLUMN.matcher(text).matches()) {
      return -1;
    }
    int value = Integer.parseInt(text);
    return value > CsvMapping.MAX_COLUMN_INDEX ? -1 : value;
  }
}
