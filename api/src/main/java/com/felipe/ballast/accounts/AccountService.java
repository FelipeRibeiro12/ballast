package com.felipe.ballast.accounts;

import com.felipe.ballast.web.Constraints;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;

// Assume entrada já validada e normalizada na borda. Todo método recebe o dono vindo da sessão.
@Service
class AccountService {

  private static final String NAME_UNIQUE_INDEX = "conta_usuario_nome_unico";
  private static final String PAYMENT_FK = "cartao_pagamento_mesmo_usuario_e_tipo";
  private static final String PERMITTED_CATEGORY_FK =
      "conta_categoria_permitida_categoria_despesa_do_usuario";

  // 9007199254740991 centavos em reais. Comparado com compareTo: a escala não conta.
  private static final BigDecimal BALANCE_LIMIT = new BigDecimal("90071992547409.91");

  private final AccountRepository accounts;
  private final AccountQueries queries;
  private final AccountDeletionQueries deletion;
  private final WriteTransaction transaction;

  AccountService(
      AccountRepository accounts,
      AccountQueries queries,
      AccountDeletionQueries deletion,
      WriteTransaction transaction) {
    this.accounts = accounts;
    this.queries = queries;
    this.deletion = deletion;
    this.transaction = transaction;
  }

  enum DeleteResult {
    DELETED,
    NOT_FOUND,
    PAYS_A_CARD,
    PAID_A_CARD_INVOICE
  }

  Optional<AccountDeletionQueries.Summary> deletionSummary(UUID userId, UUID id) {
    return deletion.exists(userId, id)
        ? Optional.of(deletion.summary(userId, id))
        : Optional.empty();
  }

  // Tudo numa transação: ou a conta some com o que é dela, ou nada muda (ADR-036). As contas do
  // outro lado das transferências são travadas junto, em ordem de id, porque o saldo delas passa
  // a depender de lançamento comum no lugar da perna.
  DeleteResult delete(UUID userId, UUID id) {
    return transaction.execute(status -> deleteLocked(userId, id));
  }

  private DeleteResult deleteLocked(UUID userId, UUID id) {
    if (!deletion.exists(userId, id)) {
      return DeleteResult.NOT_FOUND;
    }
    // Ordem: templates, lançamentos da conta, contas. É a ordem do job e do reativar (template e
    // depois conta) e de editar lançamento (lançamento e depois conta).
    deletion.lockTemplates(userId, id);
    deletion.lockEntries(userId, id);
    if (!deletion.lockAccountAndCounterparts(userId, id).contains(id)) {
      return DeleteResult.NOT_FOUND;
    }
    // Uma transferência criada antes da trava desta conta pode ter trazido outra conta. Depois da
    // trava não entra nenhuma; repetir trava a que faltou, fora da ordem, o que é raro.
    deletion.lockAccountAndCounterparts(userId, id);
    if (deletion.paysACard(userId, id)) {
      return DeleteResult.PAYS_A_CARD;
    }
    if (deletion.paidACardInvoice(userId, id)) {
      return DeleteResult.PAID_A_CARD_INVOICE;
    }
    deletion.turnForeignLegsIntoPlainEntries(userId, id);
    deletion.deleteEntries(userId, id);
    deletion.detachOccurrencesOfAccountTemplates(userId, id);
    deletion.deleteTemplates(userId, id);
    return deletion.deleteAccountRows(userId, id) == 0
        ? DeleteResult.NOT_FOUND
        : DeleteResult.DELETED;
  }

  sealed interface CreateResult {
    record Created(AccountSummary account) implements CreateResult {}

    enum Rejected implements CreateResult {
      NAME_TAKEN,
      PAYMENT_ACCOUNT_NOT_FOUND,
      PAYMENT_ACCOUNT_NOT_ELIGIBLE,
      CATEGORY_NOT_FOUND
    }
  }

  // Campos nulos não mudam. paymentAccountId nulo com paymentAccountGiven true quer dizer que o
  // texto enviado não era UUID.
  record UpdateInput(
      String name,
      BigDecimal initialBalance,
      Boolean archived,
      boolean cardGiven,
      BigDecimal limit,
      boolean paymentAccountGiven,
      UUID paymentAccountId) {}

  sealed interface UpdateResult {
    record Updated(AccountSummary account) implements UpdateResult {}

    enum Rejected implements UpdateResult {
      NOT_FOUND,
      NAME_TAKEN,
      CRYPTO_WITH_BALANCE,
      INVESTMENT_WITH_BALANCE,
      CARD_NOT_ALLOWED,
      PAYMENT_ACCOUNT_NOT_FOUND,
      PAYMENT_ACCOUNT_NOT_ELIGIBLE,
      BALANCE_OUT_OF_RANGE
    }
  }

  sealed interface ReplaceResult {
    record Replaced(AccountSummary account) implements ReplaceResult {}

    enum Rejected implements ReplaceResult {
      ACCOUNT_NOT_FOUND,
      NOT_BENEFIT,
      ACCOUNT_ARCHIVED,
      CATEGORY_NOT_FOUND
    }
  }

  // Conta, cartão e lista de categorias na mesma transação: ou nasce tudo, ou nada. A violação do
  // índice de nome e a das FKs saem da transação já com rollback e são capturadas aqui fora. Não há
  // SELECT de nome antes: duas requisições simultâneas passariam juntas, só o índice segura.
  // permitted é nulo fora de conta de benefício.
  CreateResult create(
      UUID userId,
      String name,
      AccountType type,
      BigDecimal initialBalance,
      CardInput card,
      List<UUID> permitted) {
    try {
      return transaction.execute(
          status -> createLocked(userId, name, type, initialBalance, card, permitted));
    } catch (DataIntegrityViolationException e) {
      if (isDuplicateName(e)) {
        return CreateResult.Rejected.NAME_TAKEN;
      }
      return rejectionOf(e).orElseThrow(() -> e);
    }
  }

  // Troca a lista inteira. A conta fica travada FOR UPDATE até o commit (V12): lançamento de
  // despesa nessa conta espera aqui e é avaliado contra a lista nova. Apagar e reinserir na mesma
  // transação não deixa o gatilho ver a conta sem lista no meio.
  ReplaceResult replacePermitted(UUID userId, UUID id, List<UUID> ids) {
    try {
      return transaction.execute(status -> replaceLocked(userId, id, ids));
    } catch (DataIntegrityViolationException e) {
      if (Constraints.isViolated(e, PERMITTED_CATEGORY_FK)) {
        return ReplaceResult.Rejected.CATEGORY_NOT_FOUND;
      }
      throw e;
    }
  }

  private ReplaceResult replaceLocked(UUID userId, UUID id, List<UUID> ids) {
    Optional<AccountQueries.LockedAccount> account = queries.lockAccount(userId, id);
    if (account.isEmpty()) {
      return ReplaceResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    if (account.get().type() != AccountType.BENEFIT) {
      return ReplaceResult.Rejected.NOT_BENEFIT;
    }
    if (account.get().archived()) {
      return ReplaceResult.Rejected.ACCOUNT_ARCHIVED;
    }
    if (queries.countExpenseCategories(userId, ids) != ids.size()) {
      return ReplaceResult.Rejected.CATEGORY_NOT_FOUND;
    }
    queries.deletePermitted(userId, id);
    queries.insertPermitted(userId, id, ids);
    return queries.find(userId, id).<ReplaceResult>map(ReplaceResult.Replaced::new).orElseThrow();
  }

  private CreateResult createLocked(
      UUID userId,
      String name,
      AccountType type,
      BigDecimal initialBalance,
      CardInput card,
      List<UUID> permitted) {
    if (permitted != null
        && queries.countExpenseCategories(userId, permitted) != permitted.size()) {
      return CreateResult.Rejected.CATEGORY_NOT_FOUND;
    }
    if (card != null) {
      // Conta de outro usuário e conta inexistente dão a mesma resposta. A conferência de dentro
      // só dá a mensagem; quem segura a corrida é a FK composta da V10.
      Optional<AccountQueries.PaymentAccount> payment =
          card.paymentAccountId() == null
              ? Optional.empty()
              : queries.findPaymentAccount(userId, card.paymentAccountId());
      if (payment.isEmpty()) {
        return CreateResult.Rejected.PAYMENT_ACCOUNT_NOT_FOUND;
      }
      if (!payment.get().eligible()) {
        return CreateResult.Rejected.PAYMENT_ACCOUNT_NOT_ELIGIBLE;
      }
    }
    UUID id = accounts.saveAndFlush(new Account(userId, name, type, initialBalance)).getId();
    if (card != null) {
      queries.insertCard(userId, id, card);
    }
    if (permitted != null) {
      queries.insertPermitted(userId, id, permitted);
    }
    return queries.find(userId, id).<CreateResult>map(CreateResult.Created::new).orElseThrow();
  }

  List<AccountSummary> list(UUID userId, boolean archived) {
    return queries.list(userId, archived);
  }

  Optional<AccountSummary> find(UUID userId, UUID id) {
    return queries.find(userId, id);
  }

  // A conta fica travada até o commit, como nas outras escritas dela; esperar mais de 2 s vira
  // 503. O nome repetido e a FK da conta de pagamento saem da transação já com rollback.
  UpdateResult update(UUID userId, UUID id, UpdateInput input) {
    try {
      return transaction.execute(status -> updateLocked(status, userId, id, input));
    } catch (DataIntegrityViolationException e) {
      if (Constraints.isViolated(e, NAME_UNIQUE_INDEX)) {
        return UpdateResult.Rejected.NAME_TAKEN;
      }
      if (Constraints.isViolated(e, PAYMENT_FK)) {
        return UpdateResult.Rejected.PAYMENT_ACCOUNT_NOT_FOUND;
      }
      throw e;
    }
  }

  private UpdateResult updateLocked(
      TransactionStatus status, UUID userId, UUID id, UpdateInput input) {
    Optional<AccountType> type = queries.lockAccount(userId, id).map(a -> a.type());
    if (type.isEmpty()) {
      return UpdateResult.Rejected.NOT_FOUND;
    }
    if (type.get() == AccountType.CRYPTO
        && input.initialBalance() != null
        && input.initialBalance().signum() != 0) {
      return UpdateResult.Rejected.CRYPTO_WITH_BALANCE;
    }
    if (type.get() == AccountType.INVESTMENT
        && input.initialBalance() != null
        && input.initialBalance().signum() != 0) {
      return UpdateResult.Rejected.INVESTMENT_WITH_BALANCE;
    }
    if (input.cardGiven() && type.get() != AccountType.CREDIT_CARD) {
      return UpdateResult.Rejected.CARD_NOT_ALLOWED;
    }
    if (input.paymentAccountGiven()) {
      Optional<AccountQueries.PaymentAccount> payment =
          input.paymentAccountId() == null
              ? Optional.empty()
              : queries.findPaymentAccount(userId, input.paymentAccountId());
      if (payment.isEmpty()) {
        return UpdateResult.Rejected.PAYMENT_ACCOUNT_NOT_FOUND;
      }
      if (!payment.get().eligible()) {
        return UpdateResult.Rejected.PAYMENT_ACCOUNT_NOT_ELIGIBLE;
      }
    }
    queries.updateAccount(userId, id, input.name(), input.initialBalance(), input.archived());
    if (input.cardGiven()) {
      queries.updateCard(userId, id, input.limit(), input.paymentAccountId());
    }
    AccountSummary updated = queries.find(userId, id).orElseThrow();
    // O saldo inicial novo entra no saldo; a faixa é a do JSON em centavos (ADR-023).
    if (updated.balance().abs().compareTo(BALANCE_LIMIT) > 0) {
      status.setRollbackOnly();
      return UpdateResult.Rejected.BALANCE_OUT_OF_RANGE;
    }
    return new UpdateResult.Updated(updated);
  }

  // Só as FKs da conta de pagamento e da categoria chegam aqui com dado vindo do cliente. Quando a
  // conferência de dentro passou e a FK recusou, o recurso mudou no meio do caminho.
  private static Optional<CreateResult> rejectionOf(Throwable error) {
    if (Constraints.isViolated(error, PAYMENT_FK)) {
      return Optional.of(CreateResult.Rejected.PAYMENT_ACCOUNT_NOT_FOUND);
    }
    if (Constraints.isViolated(error, PERMITTED_CATEGORY_FK)) {
      return Optional.of(CreateResult.Rejected.CATEGORY_NOT_FOUND);
    }
    return Optional.empty();
  }

  private static boolean isDuplicateName(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && NAME_UNIQUE_INDEX.equals(violation.getConstraintName())) {
        return true;
      }
    }
    return false;
  }
}
