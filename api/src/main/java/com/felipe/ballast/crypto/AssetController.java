package com.felipe.ballast.crypto;

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
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "cripto")
@RestController
class AssetController {

  private static final Logger log = LoggerFactory.getLogger(AssetController.class);

  // Mesmo corpo para ativo de outro usuário, inexistente e id que não é UUID.
  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");
  private static final ErrorResponse INVALID_CONTRACT =
      new ErrorResponse("contrato inválido para a rede");
  private static final ErrorResponse DUPLICATE_ASSET = new ErrorResponse("ativo já cadastrado");
  private static final ErrorResponse DUPLICATE_QUOTE =
      new ErrorResponse("já existe cotação nesse momento");
  private static final ErrorResponse INVALID_MOMENT =
      new ErrorResponse("momento deve ser data e hora ISO-8601 com fuso, ano entre 1900 e 2199");

  private static final ErrorResponse FUTURE_MOMENT =
      new ErrorResponse("momento não pode ser no futuro");

  private static final int MIN_YEAR = 1900;
  private static final int MAX_YEAR = 2199;

  private final AssetService assets;
  private final Clock clock;

  AssetController(AssetService assets, Clock clock) {
    this.assets = assets;
    this.clock = clock;
  }

  @Operation(
      operationId = "postAtivosCripto",
      summary = "Cadastra um ativo de cripto do usuário",
      description =
          "Identidade é rede mais contrato; contrato nulo é o nativo da rede. Nasce confiável.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Ativo criado",
      content = @Content(schema = @Schema(implementation = AssetResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, inclusive campo não declarado e \"contrato inválido para a rede\"",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "O usuário já tem um ativo com essa rede e esse contrato",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "Corpo maior que 4 KiB",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "415",
      description = "Content-Type diferente de application/json",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/ativos-cripto",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody AssetRequest request) {
    AssetService.CreateResult result =
        assets.create(
            user.id(),
            request.network(),
            request.contract(),
            request.symbol(),
            request.name(),
            request.decimals());
    return switch (result) {
      case AssetService.CreateResult.Created created -> {
        log.info("ativo cripto criado usuario_id={} ativo_id={}", user.id(), created.asset().id());
        yield ResponseEntity.status(HttpStatus.CREATED).body(AssetResponse.from(created.asset()));
      }
      case AssetService.CreateResult.Rejected rejected -> {
        log.warn("ativo cripto recusado: motivo={} usuario_id={}", rejected, user.id());
        yield switch (rejected) {
          case INVALID_CONTRACT -> ResponseEntity.badRequest().body(INVALID_CONTRACT);
          case DUPLICATE -> ResponseEntity.status(HttpStatus.CONFLICT).body(DUPLICATE_ASSET);
        };
      }
    };
  }

  @Operation(
      operationId = "getAtivosCripto",
      summary = "Lista os ativos de cripto do usuário",
      description = "Por símbolo sem diferenciar caixa, depois rede.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Ativos do usuário",
      content =
          @Content(array = @ArraySchema(schema = @Schema(implementation = AssetResponse.class))))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/ativos-cripto", produces = MediaType.APPLICATION_JSON_VALUE)
  List<AssetResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
    return assets.list(user.id()).stream().map(AssetResponse::from).toList();
  }

  @Operation(
      operationId = "postAtivosCriptoByIdCotacoes",
      summary = "Grava uma cotação manual do ativo",
      description =
          "Preço em reais por unidade, em texto. Sem momento, vale o instante da requisição.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Cotação gravada",
      content = @Content(schema = @Schema(implementation = QuoteResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Preço ou momento fora do formato, campo não declarado",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Ativo de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Já existe cotação do ativo nesse instante",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "Corpo maior que 4 KiB",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "415",
      description = "Content-Type diferente de application/json",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/ativos-cripto/{id}/cotacoes",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> quote(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody QuoteRequest request) {
    Optional<UUID> assetId = CanonicalUuid.parse(id);
    if (assetId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    Optional<Instant> moment =
        request.moment() == null ? Optional.of(clock.instant()) : parseMoment(request.moment());
    if (moment.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_MOMENT);
    }
    // Cotação manual registra um preço que já se viu; no futuro ela só apareceria numa data futura.
    if (moment.get().isAfter(clock.instant())) {
      return ResponseEntity.badRequest().body(FUTURE_MOMENT);
    }
    // O timestamptz guarda microssegundos: truncar aqui faz a resposta bater com o que ficou
    // gravado e o 409 valer para o mesmo instante que o banco enxerga.
    Instant stored = moment.get().truncatedTo(ChronoUnit.MICROS);
    return switch (assets.quote(user.id(), assetId.get(), stored, request.parsedPrice())) {
      case SAVED -> {
        log.info("cotação manual gravada usuario_id={} ativo_id={}", user.id(), assetId.get());
        yield ResponseEntity.status(HttpStatus.CREATED)
            .body(
                new QuoteResponse(
                    assetId.get(), stored, Decimals.format(request.parsedPrice()), "manual"));
      }
      case ASSET_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case DUPLICATE -> ResponseEntity.status(HttpStatus.CONFLICT).body(DUPLICATE_QUOTE);
    };
  }

  // O fuso é obrigatório: sem ele o mesmo texto seria um instante diferente em cada servidor.
  private static Optional<Instant> parseMoment(String text) {
    try {
      OffsetDateTime parsed = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      int year = parsed.toInstant().atOffset(ZoneOffset.UTC).getYear();
      if (year < MIN_YEAR || year > MAX_YEAR) {
        return Optional.empty();
      }
      return Optional.of(parsed.toInstant());
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
