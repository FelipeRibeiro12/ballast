package com.felipe.ballast.entries;

import com.felipe.ballast.limits.UserLimit;
import com.felipe.ballast.limits.UserLimits;
import com.felipe.ballast.web.WriteTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;

// Compra parcelada (ADR-032). Como o EntryService: escrita em WriteTransaction, conta travada
// antes de conferir arquivada e saldo, e a violação das FKs capturada fora da transação (dentro,
// o commit lançaria UnexpectedRollbackException). A conferência de dentro só dá a mensagem; a
// corrida de dono quem segura são as FKs compostas.
@Service
class InstallmentService {

  private static final String ACCOUNT_FK = "lancamento_conta_mesmo_usuario";
  private static final String CATEGORY_FK = "lancamento_categoria_mesmo_usuario_e_tipo";
  private static final String CARD_FK = "lancamento_parcela_conta_cartao";

  sealed interface CreateResult {
    record Created(InstallmentPurchase purchase) implements CreateResult {}

    enum Rejected implements CreateResult {
      ACCOUNT_NOT_FOUND,
      NOT_A_CARD,
      ACCOUNT_ARCHIVED,
      CATEGORY_NOT_FOUND,
      INSTALLMENT_TOO_SMALL,
      BALANCE_OUT_OF_RANGE
    }
  }

  sealed interface UpdateResult {
    record Updated(InstallmentPurchase purchase) implements UpdateResult {}

    enum Rejected implements UpdateResult {
      NOT_FOUND,
      CATEGORY_NOT_FOUND
    }
  }

  enum DeleteResult {
    DELETED,
    NOT_FOUND,
    BALANCE_OUT_OF_RANGE
  }

  // Campos nulos não mudam. descriptionGiven separa "não mandou" de "mandou vazio" (limpa).
  record Changes(String title, boolean descriptionGiven, String description, UUID categoryId) {}

  private final InstallmentQueries installments;
  private final EntryQueries entries;
  private final WriteTransaction transaction;
  private final UserLimits limits;

  InstallmentService(
      InstallmentQueries installments,
      EntryQueries entries,
      WriteTransaction transaction,
      UserLimits limits) {
    this.limits = limits;
    this.installments = installments;
    this.entries = entries;
    this.transaction = transaction;
  }

  CreateResult create(UUID userId, InstallmentInput input) {
    if (input.accountId() == null) {
      return CreateResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    // Parcela de zero centavo não existe: a divisão truncada deixaria todas menos a última em 0.
    if (InstallmentPlan.each(input.total(), input.count()).signum() == 0) {
      return CreateResult.Rejected.INSTALLMENT_TOO_SMALL;
    }
    try {
      return transaction.execute(status -> createLocked(status, userId, input));
    } catch (DataIntegrityViolationException e) {
      return createRejectionOf(e).orElseThrow(() -> e);
    }
  }

  private CreateResult createLocked(TransactionStatus status, UUID userId, InstallmentInput input) {
    limits.checkRoom(userId, UserLimit.INSTALLMENT_PURCHASES);
    Optional<Boolean> archived = entries.lockAccount(userId, input.accountId());
    if (archived.isEmpty()) {
      return CreateResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    Optional<CardBilling> billing = entries.findCardBilling(userId, input.accountId());
    if (billing.isEmpty()) {
      return CreateResult.Rejected.NOT_A_CARD;
    }
    if (archived.get()) {
      return CreateResult.Rejected.ACCOUNT_ARCHIVED;
    }
    if (input.categoryId() == null
        || !entries.categoryExists(userId, input.categoryId(), EntryType.EXPENSE)) {
      return CreateResult.Rejected.CATEGORY_NOT_FOUND;
    }
    List<InstallmentPlan.Part> parts =
        InstallmentPlan.of(input.total(), input.count(), billing.get(), input.date());
    UUID firstId = newId();
    for (InstallmentPlan.Part part : parts) {
      UUID id = part.number() == 1 ? firstId : newId();
      installments.insertInstallment(userId, id, firstId, input, part, input.count());
    }
    if (!entries.balanceFits(userId, input.accountId())) {
      status.setRollbackOnly();
      return CreateResult.Rejected.BALANCE_OUT_OF_RANGE;
    }
    return installments
        .find(userId, firstId)
        .<CreateResult>map(CreateResult.Created::new)
        .orElseThrow(() -> new IllegalStateException("compra gravada não encontrada"));
  }

  Optional<InstallmentPurchase> find(UUID userId, UUID purchaseId) {
    return installments.find(userId, purchaseId);
  }

  // Trava as parcelas e muda todas na mesma transação. Não mexe em valor nem em conta, então não
  // precisa travar a conta; se precisasse, as linhas de lançamento viriam antes dela, como no
  // EntryService.
  UpdateResult update(UUID userId, UUID purchaseId, Changes changes) {
    try {
      return transaction.execute(status -> updateLocked(userId, purchaseId, changes));
    } catch (DataIntegrityViolationException e) {
      if (isConstraint(e, CATEGORY_FK)) {
        return UpdateResult.Rejected.CATEGORY_NOT_FOUND;
      }
      throw e;
    }
  }

  private UpdateResult updateLocked(UUID userId, UUID purchaseId, Changes changes) {
    if (installments.lockInstallments(userId, purchaseId).isEmpty()) {
      return UpdateResult.Rejected.NOT_FOUND;
    }
    if (changes.categoryId() != null
        && !entries.categoryExists(userId, changes.categoryId(), EntryType.EXPENSE)) {
      return UpdateResult.Rejected.CATEGORY_NOT_FOUND;
    }
    installments.updateTexts(
        userId,
        purchaseId,
        changes.title(),
        changes.descriptionGiven(),
        changes.description(),
        changes.categoryId());
    return installments
        .find(userId, purchaseId)
        .<UpdateResult>map(UpdateResult.Updated::new)
        .orElse(UpdateResult.Rejected.NOT_FOUND);
  }

  // Parcelas primeiro, conta depois: a mesma ordem do PUT e do DELETE de um lançamento. Apagar
  // despesa sobe o saldo do cartão, e um cartão com receita grande pode passar do teto.
  DeleteResult delete(UUID userId, UUID purchaseId) {
    return transaction.execute(status -> deleteLocked(status, userId, purchaseId));
  }

  private DeleteResult deleteLocked(TransactionStatus status, UUID userId, UUID purchaseId) {
    if (installments.lockInstallments(userId, purchaseId).isEmpty()) {
      return DeleteResult.NOT_FOUND;
    }
    Optional<InstallmentPurchase> purchase = installments.find(userId, purchaseId);
    if (purchase.isEmpty()) {
      return DeleteResult.NOT_FOUND;
    }
    UUID account = purchase.get().accountId();
    entries.lockAccount(userId, account);
    if (installments.deleteAll(userId, purchaseId) == 0) {
      return DeleteResult.NOT_FOUND;
    }
    if (!entries.balanceFits(userId, account)) {
      status.setRollbackOnly();
      return DeleteResult.BALANCE_OUT_OF_RANGE;
    }
    return DeleteResult.DELETED;
  }

  private static UUID newId() {
    // O gerador do Hibernate não usa a sessão; é o mesmo dos @UuidGenerator das entidades. O id
    // da primeira parcela precisa existir antes do INSERT, porque ela aponta para si mesma.
    return UuidVersion7Strategy.INSTANCE.generateUuid(null);
  }

  private static Optional<CreateResult.Rejected> createRejectionOf(Throwable error) {
    if (isConstraint(error, ACCOUNT_FK)) {
      return Optional.of(CreateResult.Rejected.ACCOUNT_NOT_FOUND);
    }
    if (isConstraint(error, CARD_FK)) {
      return Optional.of(CreateResult.Rejected.NOT_A_CARD);
    }
    if (isConstraint(error, CATEGORY_FK)) {
      return Optional.of(CreateResult.Rejected.CATEGORY_NOT_FOUND);
    }
    return Optional.empty();
  }

  // O INSERT pelo JdbcClient chega com o nome da constraint no PSQLException da cadeia de causas.
  private static boolean isConstraint(Throwable error, String name) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql
          && psql.getServerErrorMessage() != null
          && name.equals(psql.getServerErrorMessage().getConstraint())) {
        return true;
      }
    }
    return false;
  }
}
