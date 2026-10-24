package com.felipe.ballast.entries;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.CanonicalUuid;
import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão. O id da compra é o da primeira parcela: outra parcela, compra de
// outro usuário, inexistente e id malformado respondem 404 com o mesmo corpo.
@Tag(name = "compras-parceladas")
@RestController
class InstallmentController {

  private static final Logger log = LoggerFactory.getLogger(InstallmentController.class);

  private static final ErrorResponse NOT_FOUND = new ErrorResponse("não encontrado");
  private static final ErrorResponse ACCOUNT_NOT_FOUND = new ErrorResponse("conta não encontrada");
  private static final ErrorResponse ACCOUNT_ARCHIVED = new ErrorResponse("conta arquivada");
  private static final ErrorResponse NOT_A_CARD =
      new ErrorResponse("compra parcelada só em cartão de crédito");
  private static final ErrorResponse CATEGORY_NOT_FOUND =
      new ErrorResponse("categoria não encontrada");
  private static final ErrorResponse TOO_SMALL =
      new ErrorResponse("cada parcela precisa ter ao menos 1 centavo");
  private static final ErrorResponse BALANCE_OUT_OF_RANGE =
      new ErrorResponse("saldo da conta fora do limite");
  private static final ErrorResponse PURCHASE_OUT_OF_RANGE =
      new ErrorResponse("compra fora do limite");
  private static final ErrorResponse NOTHING_TO_CHANGE =
      new ErrorResponse("informe titulo, descricao ou categoriaId");

  private final InstallmentService installments;

  InstallmentController(InstallmentService installments) {
    this.installments = installments;
  }

  @Operation(
      operationId = "postComprasParceladas",
      summary = "Cria uma compra parcelada no cartão",
      description =
          "Cria de 2 a 48 parcelas de despesa realizada na conta de cartão, numa transação só. O"
              + " saldo do cartão cai pelo total na hora, e cada parcela aparece na fatura do seu"
              + " vencimento. Conta e categoria precisam ser do usuário; conta inexistente, de"
              + " outro usuário e id inválido têm a mesma mensagem.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Compra criada",
      content = @Content(schema = @Schema(implementation = InstallmentResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Entrada inválida, inclusive campo não declarado; \"conta não encontrada\", \"compra"
              + " parcelada só em cartão de crédito\", \"conta arquivada\", \"categoria não"
              + " encontrada\", \"cada parcela precisa ter ao menos 1 centavo\", \"saldo da"
              + " conta fora do limite\" ou \"compra fora do limite\"",
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
      responseCode = "409",
      description =
          "Teto de compras parceladas do usuário atingido (limite de compras parceladas atingido)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível ou trava ocupada por mais de 2 s; pode tentar de novo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/compras-parceladas",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody InstallmentRequest request) {
    InstallmentInput input =
        new InstallmentInput(
            request.title(),
            request.description(),
            request.total(),
            request.count(),
            CanonicalUuid.parse(request.categoryId()).orElse(null),
            CanonicalUuid.parse(request.accountId()).orElse(null),
            request.parsedDate());
    InstallmentService.CreateResult result = installments.create(user.id(), input);
    if (result instanceof InstallmentService.CreateResult.Created created) {
      log.info(
          "compra parcelada criada usuario_id={} compra_id={} parcelas={}",
          user.id(),
          created.purchase().id(),
          request.count());
      return InstallmentResponse.fits(created.purchase())
          ? ResponseEntity.status(HttpStatus.CREATED)
              .body(InstallmentResponse.from(created.purchase()))
          : ResponseEntity.badRequest().body(PURCHASE_OUT_OF_RANGE);
    }
    return switch ((InstallmentService.CreateResult.Rejected) result) {
      case ACCOUNT_NOT_FOUND -> ResponseEntity.badRequest().body(ACCOUNT_NOT_FOUND);
      case NOT_A_CARD -> ResponseEntity.badRequest().body(NOT_A_CARD);
      case ACCOUNT_ARCHIVED -> ResponseEntity.badRequest().body(ACCOUNT_ARCHIVED);
      case CATEGORY_NOT_FOUND -> ResponseEntity.badRequest().body(CATEGORY_NOT_FOUND);
      case INSTALLMENT_TOO_SMALL -> ResponseEntity.badRequest().body(TOO_SMALL);
      case BALANCE_OUT_OF_RANGE -> ResponseEntity.badRequest().body(BALANCE_OUT_OF_RANGE);
    };
  }

  @Operation(
      operationId = "getComprasParceladasById",
      summary = "Devolve uma compra parcelada",
      description =
          "O id é o da primeira parcela. valorTotalCentavos é a soma das parcelas que ainda"
              + " existem: depois de editar ou apagar uma parcela em /lancamentos, ele muda."
              + " parcelas continua sendo o número de parcelas da compra ao nascer.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Compra",
      content = @Content(schema = @Schema(implementation = InstallmentResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "\"compra fora do limite\": a soma das parcelas passa de ±9007199254740991 centavos",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Compra de outro usuário, inexistente, id de outra parcela ou id que não é UUID; todos"
              + " com o mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível; pode tentar de novo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/compras-parceladas/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> find(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    return CanonicalUuid.parse(id)
        .flatMap(purchaseId -> installments.find(user.id(), purchaseId))
        .<ResponseEntity<?>>map(
            found ->
                InstallmentResponse.fits(found)
                    ? ResponseEntity.ok(InstallmentResponse.from(found))
                    : ResponseEntity.badRequest().body(PURCHASE_OUT_OF_RANGE))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND));
  }

  @Operation(
      operationId = "patchComprasParceladasById",
      summary = "Muda o texto e a categoria da compra inteira",
      description =
          "Aplica titulo, descricao e categoriaId em todas as parcelas na mesma transação. Pelo"
              + " menos um dos três; campo ausente não muda, e descricao nula ou vazia limpa a"
              + " descrição."
              + " Valor, número de parcelas, data e conta não se editam: apague a compra e lance de"
              + " novo. Vale também com o cartão arquivado.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Compra com os valores novos",
      content = @Content(schema = @Schema(implementation = InstallmentResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Nenhum campo, campo não declarado ou valor inválido; \"categoria não encontrada\" ou"
              + " \"compra fora do limite\"",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Compra de outro usuário, inexistente, id de outra parcela ou id que não é UUID; todos"
              + " com o mesmo corpo",
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
      path = "/compras-parceladas/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody InstallmentUpdateRequest request) {
    if (request.isEmpty()) {
      return ResponseEntity.badRequest().body(NOTHING_TO_CHANGE);
    }
    Optional<UUID> purchaseId = CanonicalUuid.parse(id);
    if (purchaseId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    UUID categoryId = null;
    if (request.categoryId() != null) {
      categoryId = CanonicalUuid.parse(request.categoryId()).orElse(null);
      if (categoryId == null) {
        // Existência da compra antes: id de categoria inválido não pode revelar compra alheia.
        return installments.find(user.id(), purchaseId.get()).isPresent()
            ? ResponseEntity.badRequest().body(CATEGORY_NOT_FOUND)
            : ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      }
    }
    String description =
        request.description() == null || request.description().isEmpty()
            ? null
            : request.description();
    InstallmentService.UpdateResult result =
        installments.update(
            user.id(),
            purchaseId.get(),
            new InstallmentService.Changes(
                request.title(), request.description() != null, description, categoryId));
    return switch (result) {
      case InstallmentService.UpdateResult.Updated updated -> {
        log.info(
            "compra parcelada atualizada usuario_id={} compra_id={}", user.id(), purchaseId.get());
        yield InstallmentResponse.fits(updated.purchase())
            ? ResponseEntity.ok(InstallmentResponse.from(updated.purchase()))
            : ResponseEntity.badRequest().body(PURCHASE_OUT_OF_RANGE);
      }
      case InstallmentService.UpdateResult.Rejected rejected ->
          switch (rejected) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
            case CATEGORY_NOT_FOUND -> ResponseEntity.badRequest().body(CATEGORY_NOT_FOUND);
          };
    };
  }

  @Operation(
      operationId = "deleteComprasParceladasById",
      summary = "Apaga a compra parcelada inteira",
      description =
          "Apaga todas as parcelas que ainda existem numa instrução só. O saldo do cartão volta.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Compra apagada")
  @ApiResponse(
      responseCode = "400",
      description =
          "\"saldo da conta fora do limite\": apagar tiraria o saldo da faixa e nada é apagado",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "Compra de outro usuário, inexistente, id de outra parcela ou id que não é UUID; todos"
              + " com o mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível ou trava ocupada por mais de 2 s; pode tentar de novo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/compras-parceladas/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> purchaseId = CanonicalUuid.parse(id);
    if (purchaseId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    return switch (installments.delete(user.id(), purchaseId.get())) {
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case BALANCE_OUT_OF_RANGE -> ResponseEntity.badRequest().body(BALANCE_OUT_OF_RANGE);
      case DELETED -> {
        log.info(
            "compra parcelada apagada usuario_id={} compra_id={}", user.id(), purchaseId.get());
        yield ResponseEntity.noContent().build();
      }
    };
  }
}
