package com.felipe.ballast.accounts;

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

  private final AccountRepository accounts;
  private final AccountQueries queries;
  private final WriteTransaction transaction;

  AccountService(AccountRepository accounts, AccountQueries queries, WriteTransaction transaction) {
    this.accounts = accounts;
    this.queries = queries;
    this.transaction = transaction;
  }

  // Vazio quando o nome já existe para o usuário. Sem @Transactional de propósito: o
  // saveAndFlush abre a própria transação, e a violação do índice precisa ser capturada fora
  // dela. Não há SELECT antes: duas requisições simultâneas passariam juntas, só o índice segura.
  Optional<AccountSummary> create(
      UUID userId, String name, AccountType type, BigDecimal initialBalance) {
    UUID id;
    try {
      id = accounts.saveAndFlush(new Account(userId, name, type, initialBalance)).getId();
    } catch (DataIntegrityViolationException e) {
      if (isDuplicateName(e)) {
        return Optional.empty();
      }
      throw e;
    }
    return queries.find(userId, id);
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
