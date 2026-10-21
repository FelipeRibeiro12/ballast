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

// Assume entrada já validada e normalizada na borda. Todo método recebe o dono vindo da sessão.
@Service
class AccountService {

  private static final String NAME_UNIQUE_INDEX = "conta_usuario_nome_unico";
  private static final String PAYMENT_FK = "cartao_pagamento_mesmo_usuario_e_tipo";
  private static final String PERMITTED_CATEGORY_FK =
      "conta_categoria_permitida_categoria_despesa_do_usuario";

  private final AccountRepository accounts;
  private final AccountQueries queries;
  private final WriteTransaction transaction;

  AccountService(AccountRepository accounts, AccountQueries queries, WriteTransaction transaction) {
    this.accounts = accounts;
    this.queries = queries;
    this.transaction = transaction;
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

  sealed interface ReplaceResult {
    record Replaced(AccountSummary account) implements ReplaceResult {}

    enum Rejected implements ReplaceResult {
      ACCOUNT_NOT_FOUND,
      NOT_BENEFIT,
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
    Optional<AccountType> type = queries.lockAccountType(userId, id);
    if (type.isEmpty()) {
      return ReplaceResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    if (type.get() != AccountType.BENEFIT) {
      return ReplaceResult.Rejected.NOT_BENEFIT;
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

  // Vazio para conta de outro usuário ou inexistente, sem distinguir. O UPDATE espera no máximo
  // 2 s pela trava da conta, como as outras escritas dela; estourar vira 503.
  Optional<AccountSummary> setArchived(UUID userId, UUID id, boolean archived) {
    Integer updated = transaction.execute(status -> accounts.updateArchived(userId, id, archived));
    if (updated == null || updated == 0) {
      return Optional.empty();
    }
    return queries.find(userId, id);
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
