package com.felipe.ballast.accounts;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.identity.MfaGate;
import com.felipe.ballast.web.CanonicalUuid;
import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "contas")
@RestController
class AccountController {

  private static final Logger log = LoggerFactory.getLogger(AccountController.class);

  // Mesmo corpo para conta de outro usuário, conta inexistente e id que não é UUID: diferenciar
  // diria ao cliente quais ids existem.
  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");

  private static final ErrorResponse NAME_TAKEN =
      new ErrorResponse("já existe uma conta com esse nome");

  private static final ErrorResponse CARD_REQUIRED =
      new ErrorResponse("cartao é obrigatório para conta de cartão de crédito");

  private static final ErrorResponse CARD_NOT_ALLOWED =
      new ErrorResponse("cartao só vale para conta de cartão de crédito");

  private static final ErrorResponse PAYMENT_ACCOUNT_NOT_FOUND =
      new ErrorResponse("conta de pagamento não encontrada");

  private static final ErrorResponse PAYMENT_ACCOUNT_NOT_ELIGIBLE =
      new ErrorResponse("conta de pagamento deve ser uma conta corrente ou poupança ativa");

  private static final ErrorResponse CATEGORY_NOT_FOUND =
      new ErrorResponse("categoria não encontrada");

  private static final ErrorResponse PERMITTED_NOT_ALLOWED =
      new ErrorResponse("categoriasPermitidas só vale para conta de benefício");

  private static final ErrorResponse PERMITTED_REPEATED =
      new ErrorResponse("categoriasPermitidas não pode repetir categoria");

  private static final ErrorResponse NOT_BENEFIT =
      new ErrorResponse("só conta de benefício tem categorias permitidas");

  private static final ErrorResponse CRYPTO_WITH_BALANCE =
      new ErrorResponse("conta cripto não tem saldo em reais");

  private static final ErrorResponse INVESTMENT_WITH_BALANCE =
      new ErrorResponse("conta de investimento não tem saldo em reais");

  private static final ErrorResponse PAYS_A_CARD =
      new ErrorResponse("esta conta paga um cartão; troque a conta de pagamento antes");

  private static final ErrorResponse PAID_A_CARD_INVOICE =
      new ErrorResponse(
          "esta conta pagou fatura de cartão; apagar desmontaria a fatura. Arquive a conta");

  private static final ErrorResponse NOTHING_TO_UPDATE =
      new ErrorResponse("informe ao menos um campo para alterar");

  private static final ErrorResponse BALANCE_OUT_OF_RANGE =
      new ErrorResponse("saldo da conta fora do limite");

  private static final ErrorResponse INVALID_ARCHIVED_FILTER =
      new ErrorResponse("arquivadas deve ser true ou false");

  private final AccountService accounts;
  private final MfaGate mfaGate;

  AccountController(AccountService accounts, MfaGate mfaGate) {
    this.accounts = accounts;
    this.mfaGate = mfaGate;
  }

  @Operation(
      operationId = "postContas",
      summary = "Cria uma conta",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Conta criada",
      content = @Content(schema = @Schema(implementation = AccountResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, inclusive campo não declarado, cartao fora de conta de cartão (ou"
              + " ausente nela) e conta de pagamento inexistente, de outro usuário, arquivada ou"
              + " de tipo que não paga cartão",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Já existe uma conta do usuário com esse nome, sem diferenciar caixa",
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
      path = "/contas",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateAccountRequest request) {
    boolean isCard = request.type() == AccountType.CREDIT_CARD;
    if (isCard && request.card() == null) {
      return ResponseEntity.badRequest().body(CARD_REQUIRED);
    }
    if (!isCard && request.card() != null) {
      return ResponseEntity.badRequest().body(CARD_NOT_ALLOWED);
    }
    boolean isBenefit = request.type() == AccountType.BENEFIT;
    if (!isBenefit && request.permittedCategories() != null) {
      return ResponseEntity.badRequest().body(PERMITTED_NOT_ALLOWED);
    }
    // O valor da conta cripto vem das posições (ADR-035); o CHECK da V13 recusaria com 500.
    if (request.type() == AccountType.CRYPTO && request.initialBalanceCents() != 0) {
      return ResponseEntity.badRequest().body(CRYPTO_WITH_BALANCE);
    }
    // Os títulos dão o valor da conta de investimento (ADR-037), como as posições na cripto.
    if (request.type() == AccountType.INVESTMENT && request.initialBalanceCents() != 0) {
      return ResponseEntity.badRequest().body(INVESTMENT_WITH_BALANCE);
    }
    // Conta de benefício sem o campo grava lista vazia: aceita qualquer categoria.
    List<UUID> permitted = null;
    if (isBenefit) {
      PermittedCategories.Parsed parsed =
          PermittedCategories.parse(
              request.permittedCategories() == null ? List.of() : request.permittedCategories());
      if (!(parsed instanceof PermittedCategories.Parsed.Ids ids)) {
        return ResponseEntity.badRequest().body(permittedRefusal(parsed));
      }
      permitted = ids.ids();
    }
    AccountService.CreateResult result =
        accounts.create(
            user.id(),
            request.name(),
            request.type(),
            request.initialBalance(),
            cardInput(request.card()),
            permitted);
    return switch (result) {
      case AccountService.CreateResult.Created created -> {
        log.info("conta criada usuario_id={} conta_id={}", user.id(), created.account().id());
        yield ResponseEntity.status(HttpStatus.CREATED)
            .body(AccountResponse.from(created.account()));
      }
      case AccountService.CreateResult.Rejected rejected -> {
        // Sem o nome no log: é dado do usuário e o motivo da recusa já está na mensagem.
        log.warn("conta recusada: motivo={} usuario_id={}", rejected, user.id());
        yield switch (rejected) {
          case NAME_TAKEN -> ResponseEntity.status(HttpStatus.CONFLICT).body(NAME_TAKEN);
          case PAYMENT_ACCOUNT_NOT_FOUND ->
              ResponseEntity.badRequest().body(PAYMENT_ACCOUNT_NOT_FOUND);
          case PAYMENT_ACCOUNT_NOT_ELIGIBLE ->
              ResponseEntity.badRequest().body(PAYMENT_ACCOUNT_NOT_ELIGIBLE);
          case CATEGORY_NOT_FOUND -> ResponseEntity.badRequest().body(CATEGORY_NOT_FOUND);
        };
      }
    };
  }

  // Id malformado tem a mesma resposta de categoria inexistente ou de outro usuário.
  private static ErrorResponse permittedRefusal(PermittedCategories.Parsed refused) {
    return refused == PermittedCategories.Parsed.Refused.REPEATED
        ? PERMITTED_REPEATED
        : CATEGORY_NOT_FOUND;
  }

  // O texto de contaPagamentoId que não é UUID vira nulo, e o serviço responde "não encontrada".
  private static CardInput cardInput(CardRequest card) {
    if (card == null) {
      return null;
    }
    return new CardInput(
        card.limit(),
        card.closingDay(),
        card.dueDay(),
        CanonicalUuid.parse(card.paymentAccountId()).orElse(null));
  }

  @Operation(
      operationId = "getContas",
      summary = "Lista as contas do usuário",
      description =
          "Ordenada por nome sem diferenciar caixa. Sem o parâmetro, devolve só as ativas.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Contas ativas, ou só as arquivadas com arquivadas=true",
      content =
          @Content(array = @ArraySchema(schema = @Schema(implementation = AccountResponse.class))))
  @ApiResponse(
      responseCode = "400",
      description = "arquivadas com valor diferente de true e false",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/contas", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(
              allowableValues = {"true", "false"},
              defaultValue = "false")
          @RequestParam(name = "arquivadas", required = false)
          String archived) {
    // Lido como texto: o conversor padrão de boolean aceita também 1, yes e on.
    boolean onlyArchived;
    if (archived == null || archived.equals("false")) {
      onlyArchived = false;
    } else if (archived.equals("true")) {
      onlyArchived = true;
    } else {
      return ResponseEntity.badRequest().body(INVALID_ARCHIVED_FILTER);
    }
    List<AccountResponse> body =
        accounts.list(user.id(), onlyArchived).stream().map(AccountResponse::from).toList();
    return ResponseEntity.ok(body);
  }

  @Operation(
      operationId = "getContasById",
      summary = "Devolve uma conta do usuário",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Conta",
      content = @Content(schema = @Schema(implementation = AccountResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Conta de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/contas/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> find(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    return CanonicalUuid.parse(id)
        .flatMap(accountId -> accounts.find(user.id(), accountId))
        .<ResponseEntity<?>>map(found -> ResponseEntity.ok(AccountResponse.from(found)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
  }

  @Operation(
      operationId = "patchContasById",
      summary = "Altera uma conta",
      description =
          "Campo ausente ou nulo não muda: arquivada, nome, saldoInicialCentavos e, em cartão,"
              + " limite e conta de pagamento. Repetir o mesmo valor devolve 200 e a mesma conta.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Conta com o estado novo",
      content = @Content(schema = @Schema(implementation = AccountResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, inclusive campo não declarado e corpo sem nenhum campo, cartao fora"
              + " de cartão, conta de pagamento inválida, saldo inicial em conta cripto ou saldo"
              + " fora do limite",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Já existe outra conta do usuário com esse nome, sem diferenciar caixa",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Conta de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
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
      responseCode = "429",
      description = "Atraso por usuário do MFA em vigor, ou limite por IP",
      headers = @Header(name = "Retry-After", required = true, schema = @Schema(type = "integer")),
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível ou conta travada por mais de 2 s",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PatchMapping(
      path = "/contas/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody UpdateAccountRequest request) {
    if (request.isEmpty()) {
      return ResponseEntity.badRequest().body(NOTHING_TO_UPDATE);
    }
    Optional<UUID> accountId = CanonicalUuid.parse(id);
    if (accountId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    UpdateAccountRequest.UpdateCardRequest card = request.card();
    boolean paymentGiven = card != null && card.paymentAccountId() != null;
    AccountService.UpdateInput input =
        new AccountService.UpdateInput(
            request.name(),
            request.initialBalance(),
            request.archived(),
            card != null,
            card == null ? null : card.limit(),
            paymentGiven,
            paymentGiven ? CanonicalUuid.parse(card.paymentAccountId()).orElse(null) : null);
    return switch (accounts.update(user.id(), accountId.get(), input)) {
      case AccountService.UpdateResult.Updated updated -> {
        log.info(
            "conta atualizada usuario_id={} conta_id={} arquivada={}",
            user.id(),
            updated.account().id(),
            updated.account().archived());
        yield ResponseEntity.ok(AccountResponse.from(updated.account()));
      }
      case AccountService.UpdateResult.Rejected rejected -> {
        log.warn("conta não atualizada: motivo={} usuario_id={}", rejected, user.id());
        yield switch (rejected) {
          case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
          case NAME_TAKEN -> ResponseEntity.status(HttpStatus.CONFLICT).body(NAME_TAKEN);
          case CRYPTO_WITH_BALANCE -> ResponseEntity.badRequest().body(CRYPTO_WITH_BALANCE);
          case INVESTMENT_WITH_BALANCE -> ResponseEntity.badRequest().body(INVESTMENT_WITH_BALANCE);
          case CARD_NOT_ALLOWED -> ResponseEntity.badRequest().body(CARD_NOT_ALLOWED);
          case PAYMENT_ACCOUNT_NOT_FOUND ->
              ResponseEntity.badRequest().body(PAYMENT_ACCOUNT_NOT_FOUND);
          case PAYMENT_ACCOUNT_NOT_ELIGIBLE ->
              ResponseEntity.badRequest().body(PAYMENT_ACCOUNT_NOT_ELIGIBLE);
          case BALANCE_OUT_OF_RANGE -> ResponseEntity.badRequest().body(BALANCE_OUT_OF_RANGE);
        };
      }
    };
  }

  @Operation(
      operationId = "deleteContasById",
      summary = "Apaga uma conta e tudo que é dela",
      description =
          "Lançamentos, recorrências, cartão, categorias permitidas e movimentos de cripto da conta"
              + " saem juntos. Numa transferência com outra conta, a perna de lá vira despesa em"
              + " Outros (saída) ou receita em Outras receitas (entrada), e o saldo dela não muda."
              + " Com o MFA ligado, o corpo {\"codigo\"} é obrigatório (código do autenticador ou de"
              + " recuperação); sem MFA, o corpo é ignorado e pode faltar.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Apagada")
  @ApiResponse(
      responseCode = "400",
      description =
          "A conta é conta de pagamento de um cartão, ou pagou fatura de cartão por transferência;"
              + " ou, com o MFA ligado, código ausente, inválido ou já usado (código inválido);"
              + " nada é apagado",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Conta de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível ou conta travada por mais de 2 s",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/contas/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody(required = false) DeleteAccountRequest request) {
    // O código vem antes de olhar a conta: assim a resposta sobre o código é a mesma para conta
    // de outro usuário e conta inexistente, e o 404 não vira pista.
    mfaGate.requireCode(user.id(), request == null ? null : request.code());
    Optional<UUID> accountId = CanonicalUuid.parse(id);
    if (accountId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return switch (accounts.delete(user.id(), accountId.get())) {
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case PAYS_A_CARD -> ResponseEntity.badRequest().body(PAYS_A_CARD);
      case PAID_A_CARD_INVOICE -> ResponseEntity.badRequest().body(PAID_A_CARD_INVOICE);
      case DELETED -> {
        log.info("conta apagada usuario_id={} conta_id={}", user.id(), accountId.get());
        yield ResponseEntity.noContent().build();
      }
    };
  }

  @Operation(
      operationId = "getContasByIdResumoExclusao",
      summary = "Conta o que sai junto ao apagar a conta",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Contagens",
      content = @Content(schema = @Schema(implementation = DeletionSummaryResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Conta de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/contas/{id}/resumo-exclusao", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> deletionSummary(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    return CanonicalUuid.parse(id)
        .flatMap(accountId -> accounts.deletionSummary(user.id(), accountId))
        .<ResponseEntity<?>>map(found -> ResponseEntity.ok(DeletionSummaryResponse.from(found)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
  }

  @Operation(
      operationId = "putContasByIdCategoriasPermitidas",
      summary = "Troca a lista de categorias permitidas de uma conta de benefício",
      description =
          "Substitui a lista inteira. Lista vazia libera qualquer categoria de despesa. Não mexe"
              + " em lançamento já gravado.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Conta com a lista nova",
      content = @Content(schema = @Schema(implementation = AccountResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, inclusive campo não declarado, mais de 50 itens, item repetido,"
              + " categoria inexistente, de outro usuário, de receita ou id malformado, e conta"
              + " que não é de benefício",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Conta de outro usuário, inexistente ou id que não é UUID; todos com o mesmo corpo",
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
      description = "Banco indisponível ou conta travada por mais de 2 s",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/contas/{id}/categorias-permitidas",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> replacePermitted(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody PermittedCategoriesRequest request) {
    Optional<UUID> accountId = CanonicalUuid.parse(id);
    if (accountId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    PermittedCategories.Parsed parsed = PermittedCategories.parse(request.categoryIds());
    if (!(parsed instanceof PermittedCategories.Parsed.Ids ids)) {
      return ResponseEntity.badRequest().body(permittedRefusal(parsed));
    }
    AccountService.ReplaceResult result =
        accounts.replacePermitted(user.id(), accountId.get(), ids.ids());
    return switch (result) {
      case AccountService.ReplaceResult.Replaced replaced -> {
        log.info(
            "categorias permitidas trocadas usuario_id={} conta_id={} quantidade={}",
            user.id(),
            replaced.account().id(),
            ids.ids().size());
        yield ResponseEntity.ok(AccountResponse.from(replaced.account()));
      }
      case AccountService.ReplaceResult.Rejected rejected ->
          switch (rejected) {
            case ACCOUNT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
            case NOT_BENEFIT -> ResponseEntity.badRequest().body(NOT_BENEFIT);
            case CATEGORY_NOT_FOUND -> ResponseEntity.badRequest().body(CATEGORY_NOT_FOUND);
          };
    };
  }
}
