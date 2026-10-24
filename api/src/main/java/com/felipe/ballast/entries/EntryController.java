package com.felipe.ballast.entries;

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
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "lancamentos")
@RestController
class EntryController {

  private static final Logger log = LoggerFactory.getLogger(EntryController.class);

  // Mesmo corpo para lançamento de outro usuário, inexistente e id que não é UUID: diferenciar
  // diria ao cliente quais ids existem.
  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");
  private static final ErrorResponse ACCOUNT_NOT_FOUND = new ErrorResponse("conta não encontrada");
  private static final ErrorResponse ACCOUNT_ARCHIVED = new ErrorResponse("conta arquivada");
  private static final ErrorResponse CRYPTO_ACCOUNT =
      new ErrorResponse("conta cripto não recebe lançamento");
  private static final ErrorResponse INVESTMENT_ACCOUNT =
      new ErrorResponse("conta de investimento não recebe lançamento");
  private static final ErrorResponse CATEGORY_NOT_FOUND =
      new ErrorResponse("categoria não encontrada");
  private static final ErrorResponse CATEGORY_NOT_PERMITTED =
      new ErrorResponse("categoria não permitida nesta conta");
  private static final ErrorResponse TRANSFER_LEG =
      new ErrorResponse("use a rota de transferência");
  private static final ErrorResponse INVALID_TYPE =
      new ErrorResponse("transferência só pela rota /transferencias");
  private static final ErrorResponse BALANCE_OUT_OF_RANGE =
      new ErrorResponse("saldo da conta fora do limite");

  private static final ErrorResponse EDIT_WHOLE_PURCHASE =
      new ErrorResponse("edite a compra inteira");
  private static final ErrorResponse DELETE_WHOLE_PURCHASE =
      new ErrorResponse("apague a compra inteira");
  private static final ErrorResponse INSTALLMENT_NOT_EXPENSE =
      new ErrorResponse("parcela de compra parcelada só pode ser despesa");

  private static final ErrorResponse PURCHASE_OUT_OF_RANGE =
      new ErrorResponse("compra fora do limite");

  private static final ErrorResponse NOT_PLANNED =
      new ErrorResponse("só ocorrência prevista pode mudar de status");
  private static final ErrorResponse INVALID_STATUS =
      new ErrorResponse("status deve ser realizado ou cancelado");
  private static final ErrorResponse CANCEL_WITH_ADJUST =
      new ErrorResponse("cancelar não aceita valorCentavos nem data");
  private static final ErrorResponse CANCEL_OCCURRENCE = new ErrorResponse("cancele a ocorrência");

  private static final ErrorResponse INVALID_FROM =
      new ErrorResponse("de deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199");
  private static final ErrorResponse INVALID_TO =
      new ErrorResponse("ate deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199");
  private static final ErrorResponse FROM_AFTER_TO =
      new ErrorResponse("de deve ser anterior ou igual a ate");
  private static final ErrorResponse RANGE_TOO_LONG =
      new ErrorResponse("o intervalo entre de e ate deve ter no máximo 366 dias");
  private static final ErrorResponse INVALID_ACCOUNT_FILTER =
      new ErrorResponse("contaId deve ser um UUID");

  // Conta os dois extremos: 2028-01-01 a 2028-12-31 são 366 dias.
  private static final long MAX_DAYS = 366;

  // "Hoje" é o dia do usuário, não o do servidor em UTC (mesmo fuso da recorrência).
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  private final EntryService entries;
  private final Clock clock;

  EntryController(EntryService entries, Clock clock) {
    this.entries = entries;
    this.clock = clock;
  }

  @Operation(
      operationId = "postLancamentos",
      summary = "Cria um lançamento realizado",
      description =
          "A data enviada vai para dataCompetencia e dataCaixa. Conta e categoria precisam ser do"
              + " usuário, e a categoria precisa ser do mesmo tipo do lançamento; os três casos de"
              + " recusa (inexistente, de outro usuário, id inválido) têm a mesma mensagem.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Lançamento criado",
      content = @Content(schema = @Schema(implementation = EntryResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, inclusive campo não declarado; \"transferência só pela rota"
              + " /transferencias\" (tipo transferencia); \"conta não encontrada\","
              + " \"conta arquivada\", \"categoria não encontrada\", \"categoria não permitida"
              + " nesta conta\" (despesa em conta de benefício, fora da lista da conta) ou"
              + " \"saldo da conta fora do limite\" (o saldo passaria de 9007199254740991"
              + " centavos, para mais ou para menos)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
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
      description = "Banco indisponível ou trava ocupada por mais de 2 s; pode tentar de novo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/lancamentos",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody EntryRequest request) {
    if (request.type() == EntryType.TRANSFER) {
      return ResponseEntity.badRequest().body(INVALID_TYPE);
    }
    EntryService.SaveResult result = entries.create(user.id(), toInput(request));
    if (result instanceof EntryService.SaveResult.Saved saved) {
      log.info("lançamento criado usuario_id={} lancamento_id={}", user.id(), saved.entry().id());
      return ResponseEntity.status(HttpStatus.CREATED).body(EntryResponse.from(saved.entry()));
    }
    return rejection((EntryService.SaveResult.Rejected) result);
  }

  @Operation(
      operationId = "getLancamentos",
      summary = "Lista os lançamentos do usuário num período",
      description =
          "Filtra por dataCaixa, com as duas pontas incluídas, do mais novo para o mais antigo"
              + " (depois criadoEm e id, também decrescentes). de e ate são obrigatórios e o"
              + " intervalo tem no máximo 366 dias contando os dois extremos. contaId, se vier,"
              + " precisa ser um UUID; conta que não é do usuário ou não existe devolve lista"
              + " vazia, sem distinguir.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Lançamentos do período",
      content =
          @Content(array = @ArraySchema(schema = @Schema(implementation = EntryResponse.class))))
  @ApiResponse(
      responseCode = "400",
      description =
          "de ou ate ausente ou fora do formato AAAA-MM-DD (ano de 1900 a 2199), de depois de"
              + " ate, intervalo maior que 366 dias ou contaId que não é UUID",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível ou trava ocupada por mais de 2 s; pode tentar de novo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/lancamentos", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(type = "string", format = "date", requiredMode = Schema.RequiredMode.REQUIRED)
          @RequestParam(name = "de", required = false)
          String from,
      @Schema(type = "string", format = "date", requiredMode = Schema.RequiredMode.REQUIRED)
          @RequestParam(name = "ate", required = false)
          String to,
      @Schema(type = "string", format = "uuid") @RequestParam(name = "contaId", required = false)
          String accountId) {
    // Lidos como texto: o conversor padrão de LocalDate aceita formatos que o contrato não prevê.
    Optional<LocalDate> fromDate = IsoDates.parse(from);
    if (fromDate.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_FROM);
    }
    Optional<LocalDate> toDate = IsoDates.parse(to);
    if (toDate.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_TO);
    }
    if (fromDate.get().isAfter(toDate.get())) {
      return ResponseEntity.badRequest().body(FROM_AFTER_TO);
    }
    if (ChronoUnit.DAYS.between(fromDate.get(), toDate.get()) + 1 > MAX_DAYS) {
      return ResponseEntity.badRequest().body(RANGE_TOO_LONG);
    }
    UUID account = null;
    if (accountId != null) {
      Optional<UUID> parsed = CanonicalUuid.parse(accountId);
      if (parsed.isEmpty()) {
        return ResponseEntity.badRequest().body(INVALID_ACCOUNT_FILTER);
      }
      account = parsed.get();
    }
    List<EntryResponse> body =
        entries.list(user.id(), fromDate.get(), toDate.get(), account).stream()
            .map(EntryResponse::from)
            .toList();
    return ResponseEntity.ok(body);
  }

  @Operation(
      operationId = "getLancamentosVencidos",
      summary = "Lista as previstas vencidas do usuário",
      description =
          "Lançamentos previstos com dataCaixa antes de hoje (America/Sao_Paulo), da mais antiga"
              + " para a mais nova (depois id), no máximo 200. maisAlgum é true quando havia mais"
              + " que isso. Nada muda sozinho: o usuário confirma ou cancela cada uma (ADR-028).",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Previstas vencidas",
      content = @Content(schema = @Schema(implementation = OverdueResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível; pode tentar de novo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/lancamentos/vencidos", produces = MediaType.APPLICATION_JSON_VALUE)
  OverdueResponse overdue(@AuthenticationPrincipal AuthenticatedUser user) {
    LocalDate today = clock.instant().atZone(ZONE).toLocalDate();
    EntryService.Overdue overdue = entries.listOverdue(user.id(), today);
    return new OverdueResponse(
        overdue.entries().stream().map(EntryResponse::from).toList(), overdue.more());
  }

  @Operation(
      operationId = "getLancamentosById",
      summary = "Devolve um lançamento do usuário",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Lançamento",
      content = @Content(schema = @Schema(implementation = EntryResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Lançamento de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível ou trava ocupada por mais de 2 s; pode tentar de novo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/lancamentos/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> find(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    return CanonicalUuid.parse(id)
        .flatMap(entryId -> entries.find(user.id(), entryId))
        .<ResponseEntity<?>>map(found -> ResponseEntity.ok(EntryResponse.from(found)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
  }

  @Operation(
      operationId = "putLancamentosById",
      summary = "Substitui um lançamento",
      description =
          "Mesmo corpo do POST, substitui todos os campos e grava a data nas duas colunas. Mover"
              + " o lançamento para uma conta arquivada é recusado (400 \"conta arquivada\"). Editar"
              + " sem trocar de conta vale mesmo com a conta arquivada, para corrigir lançamento"
              + " antigo. Numa ocorrência de recorrência marca o lançamento como desgarrado (a"
              + " regra não toca mais nele) e mantém templateId; o status não muda por aqui, e uma"
              + " ocorrência prevista não conta no saldo, então o teto só vale para realizada.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Lançamento com os valores novos",
      content = @Content(schema = @Schema(implementation = EntryResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, inclusive campo não declarado; \"transferência só pela rota"
              + " /transferencias\" (tipo transferencia); \"conta não encontrada\","
              + " \"conta arquivada\" (só ao trocar de conta), \"categoria não encontrada\","
              + " \"categoria não permitida nesta conta\" (despesa em conta de benefício, fora da"
              + " lista da conta), \"edite a compra inteira\" (parcela com título, categoria,"
              + " conta ou data diferentes), \"saldo da conta fora do limite\" (em qualquer"
              + " das contas afetadas), \"compra fora do limite\" (parcela cuja soma da compra"
              + " passaria do teto) ou \"use a rota de transferência\" (o lançamento é perna"
              + " de transferência)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Lançamento de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
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
      description = "Banco indisponível ou trava ocupada por mais de 2 s; pode tentar de novo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/lancamentos/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody EntryRequest request) {
    if (request.type() == EntryType.TRANSFER) {
      return ResponseEntity.badRequest().body(INVALID_TYPE);
    }
    Optional<UUID> entryId = CanonicalUuid.parse(id);
    if (entryId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    EntryService.SaveResult result = entries.update(user.id(), entryId.get(), toInput(request));
    if (result instanceof EntryService.SaveResult.Saved saved) {
      log.info(
          "lançamento atualizado usuario_id={} lancamento_id={}", user.id(), saved.entry().id());
      return ResponseEntity.ok(EntryResponse.from(saved.entry()));
    }
    return rejection((EntryService.SaveResult.Rejected) result);
  }

  @Operation(
      operationId = "patchLancamentosById",
      summary = "Confirma ou cancela uma ocorrência prevista",
      description =
          "Corpo {\"status\": \"realizado\"} ou {\"status\": \"cancelado\"}. Só uma ocorrência"
              + " prevista muda de status. Ao confirmar, valorCentavos e data são opcionais e"
              + " gravam o valor e a data reais (a data vale para dataCompetencia e dataCaixa);"
              + " ocorrencia_data da série não muda. Com cancelado, os dois dão 400. Confirmar"
              + " passa a contar no saldo, com"
              + " a trava e o teto do saldo do lançamento, e recusa conta arquivada; cancelar não"
              + " mexe em saldo e vale mesmo com a conta arquivada. As duas são definitivas e"
              + " deixam a ocorrência desgarrada: o job não a recria nem a altera.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Lançamento com o status novo",
      content = @Content(schema = @Schema(implementation = EntryResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "\"status é obrigatório\", \"status deve ser realizado ou cancelado\", campo não"
              + " declarado, valorCentavos ou data inválidos, valorCentavos ou data com cancelado; \"só ocorrência prevista pode mudar de status\" (o lançamento não"
              + " está previsto); \"conta arquivada\" e \"saldo da conta fora do limite\" (só ao"
              + " confirmar); \"use a rota de transferência\" (perna de transferência)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Lançamento de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
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
      description = "Banco indisponível ou trava ocupada por mais de 2 s; pode tentar de novo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PatchMapping(
      path = "/lancamentos/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> changeStatus(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody StatusRequest request) {
    Optional<TargetStatus> target = TargetStatus.fromValue(request.status());
    if (target.isEmpty()) {
      return ResponseEntity.badRequest().body(INVALID_STATUS);
    }
    if (target.get() == TargetStatus.CANCELLED && request.hasAdjustment()) {
      return ResponseEntity.badRequest().body(CANCEL_WITH_ADJUST);
    }
    Optional<UUID> entryId = CanonicalUuid.parse(id);
    if (entryId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    EntryService.SaveResult result =
        entries.changeStatus(
            user.id(), entryId.get(), target.get(), request.amount(), request.parsedDate());
    if (result instanceof EntryService.SaveResult.Saved saved) {
      log.info(
          "lançamento mudou de status usuario_id={} lancamento_id={} status={}",
          user.id(),
          saved.entry().id(),
          target.get().value());
      return ResponseEntity.ok(EntryResponse.from(saved.entry()));
    }
    return rejection((EntryService.SaveResult.Rejected) result);
  }

  @Operation(
      operationId = "deleteLancamentosById",
      summary = "Apaga um lançamento",
      description =
          "Apaga a linha, também numa conta arquivada. O saldo deixa de somá-lo. Ocorrência"
              + " prevista de recorrência não se apaga (400 \"cancele a ocorrência\"): cancele."
              + " Realizada ou cancelada pode ser apagada, e se a data é de hoje em diante e a"
              + " recorrência segue ativa, o job volta a gerar a ocorrência como prevista.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Lançamento apagado")
  @ApiResponse(
      responseCode = "400",
      description =
          "\"saldo da conta fora do limite\": apagar o lançamento tiraria o saldo da faixa de"
              + " ±9007199254740991 centavos, e nada é apagado; \"use a rota de"
              + " transferência\": o lançamento é perna de transferência; \"cancele a"
              + " ocorrência\": ocorrência prevista de recorrência",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Lançamento de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível ou trava ocupada por mais de 2 s; pode tentar de novo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/lancamentos/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> entryId = CanonicalUuid.parse(id);
    if (entryId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return switch (entries.delete(user.id(), entryId.get())) {
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case BALANCE_OUT_OF_RANGE -> ResponseEntity.badRequest().body(BALANCE_OUT_OF_RANGE);
      case TRANSFER_LEG -> ResponseEntity.badRequest().body(TRANSFER_LEG);
      case CANCEL_OCCURRENCE -> ResponseEntity.badRequest().body(CANCEL_OCCURRENCE);
      case FIRST_INSTALLMENT -> ResponseEntity.badRequest().body(DELETE_WHOLE_PURCHASE);
      case DELETED -> {
        log.info("lançamento apagado usuario_id={} lancamento_id={}", user.id(), entryId.get());
        yield ResponseEntity.noContent().build();
      }
    };
  }

  private static EntryInput toInput(EntryRequest request) {
    return new EntryInput(
        request.type(),
        request.title(),
        request.description(),
        request.amount(),
        CanonicalUuid.parse(request.categoryId()).orElse(null),
        CanonicalUuid.parse(request.accountId()).orElse(null),
        request.parsedDate());
  }

  private static ResponseEntity<?> rejection(EntryService.SaveResult.Rejected rejected) {
    return switch (rejected) {
      case ENTRY_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case ACCOUNT_NOT_FOUND -> ResponseEntity.badRequest().body(ACCOUNT_NOT_FOUND);
      case ACCOUNT_ARCHIVED -> ResponseEntity.badRequest().body(ACCOUNT_ARCHIVED);
      case CRYPTO_ACCOUNT -> ResponseEntity.badRequest().body(CRYPTO_ACCOUNT);
      case INVESTMENT_ACCOUNT -> ResponseEntity.badRequest().body(INVESTMENT_ACCOUNT);
      case CATEGORY_NOT_FOUND -> ResponseEntity.badRequest().body(CATEGORY_NOT_FOUND);
      case CATEGORY_NOT_PERMITTED -> ResponseEntity.badRequest().body(CATEGORY_NOT_PERMITTED);
      case BALANCE_OUT_OF_RANGE -> ResponseEntity.badRequest().body(BALANCE_OUT_OF_RANGE);
      case TRANSFER_LEG -> ResponseEntity.badRequest().body(TRANSFER_LEG);
      case NOT_PLANNED -> ResponseEntity.badRequest().body(NOT_PLANNED);
      case EDIT_WHOLE_PURCHASE -> ResponseEntity.badRequest().body(EDIT_WHOLE_PURCHASE);
      case INSTALLMENT_NOT_EXPENSE -> ResponseEntity.badRequest().body(INSTALLMENT_NOT_EXPENSE);
      case PURCHASE_OUT_OF_RANGE -> ResponseEntity.badRequest().body(PURCHASE_OUT_OF_RANGE);
    };
  }
}
