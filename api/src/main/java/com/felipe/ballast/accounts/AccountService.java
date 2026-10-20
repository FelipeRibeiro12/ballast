package com.felipe.ballast.accounts;

import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// Assume entrada já validada e normalizada na borda. Todo método recebe o dono vindo da sessão.
@Service
class AccountService {

  private static final String NAME_UNIQUE_INDEX = "conta_usuario_nome_unico";
  private static final String PAYMENT_FK = "cartao_pagamento_mesmo_usuario_e_tipo";

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
      PAYMENT_ACCOUNT_NOT_ELIGIBLE
    }
  }

  // Conta e cartão na mesma transação: ou nascem os dois, ou nenhum. A violação do índice de nome
  // e a da FK do cartão saem da transação já com rollback e são capturadas aqui fora. Não há
  // SELECT de nome antes: duas requisições simultâneas passariam juntas, só o índice segura.
  CreateResult create(
      UUID userId, String name, AccountType type, BigDecimal initialBalance, CardInput card) {
    try {
      return transaction.execute(status -> createLocked(userId, name, type, initialBalance, card));
    } catch (DataIntegrityViolationException e) {
      if (isDuplicateName(e)) {
        return CreateResult.Rejected.NAME_TAKEN;
      }
      return paymentRejectionOf(e).orElseThrow(() -> e);
    }
  }

  private CreateResult createLocked(
      UUID userId, String name, AccountType type, BigDecimal initialBalance, CardInput card) {
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

  // Só a FK da conta de pagamento chega aqui com dado vindo do cliente. Quando a conferência de
  // dentro passou e a FK recusou, a conta mudou no meio do caminho.
  private static Optional<CreateResult> paymentRejectionOf(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql
          && psql.getServerErrorMessage() != null
          && PAYMENT_FK.equals(psql.getServerErrorMessage().getConstraint())) {
        return Optional.of(CreateResult.Rejected.PAYMENT_ACCOUNT_NOT_FOUND);
      }
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
