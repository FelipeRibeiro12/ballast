package com.felipe.ballast.entries;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// Assume entrada já validada e normalizada na borda. Todo método recebe o dono vindo da sessão.
@Service
class EntryService {

  private static final String ACCOUNT_FK = "lancamento_conta_mesmo_usuario";
  private static final String CATEGORY_FK = "lancamento_categoria_mesmo_usuario_e_tipo";

  sealed interface SaveResult {
    record Saved(EntrySummary entry) implements SaveResult {}

    enum Rejected implements SaveResult {
      ENTRY_NOT_FOUND,
      ACCOUNT_NOT_FOUND,
      ACCOUNT_ARCHIVED,
      CATEGORY_NOT_FOUND
    }
  }

  private final EntryRepository entries;
  private final EntryQueries queries;

  EntryService(EntryRepository entries, EntryQueries queries) {
    this.entries = entries;
    this.queries = queries;
  }

  // Sem @Transactional de propósito: o saveAndFlush abre a própria transação, e a violação da FK
  // precisa ser capturada fora dela. A conferência de conta e categoria antes é só para a
  // mensagem; quem segura a corrida entre duas requisições é a FK composta.
  SaveResult create(UUID userId, EntryInput input) {
    Optional<SaveResult.Rejected> rejected = checkReferences(userId, input, false);
    if (rejected.isPresent()) {
      return rejected.get();
    }
    UUID id;
    try {
      id = entries.saveAndFlush(new Entry(userId, input)).getId();
    } catch (DataIntegrityViolationException e) {
      return rejectionOf(e).orElseThrow(() -> e);
    }
    return saved(userId, id);
  }

  // Trocar de conta exige conta ativa. Ficar na mesma conta vale mesmo arquivada, senão um
  // lançamento antigo não poderia ser corrigido depois de arquivar a conta.
  SaveResult update(UUID userId, UUID id, EntryInput input) {
    Optional<EntrySummary> current = queries.find(userId, id);
    if (current.isEmpty()) {
      return SaveResult.Rejected.ENTRY_NOT_FOUND;
    }
    boolean sameAccount = current.get().accountId().equals(input.accountId());
    Optional<SaveResult.Rejected> rejected = checkReferences(userId, input, sameAccount);
    if (rejected.isPresent()) {
      return rejected.get();
    }
    int changed;
    try {
      changed =
          entries.replace(
              userId,
              id,
              input.type().value(),
              input.title(),
              input.description(),
              input.categoryId(),
              input.accountId(),
              input.amount(),
              input.date());
    } catch (DataIntegrityViolationException e) {
      return rejectionOf(e).orElseThrow(() -> e);
    }
    return changed == 0 ? SaveResult.Rejected.ENTRY_NOT_FOUND : saved(userId, id);
  }

  // Falso para lançamento de outro usuário ou inexistente, sem distinguir.
  boolean delete(UUID userId, UUID id) {
    return entries.deleteOwned(userId, id) > 0;
  }

  Optional<EntrySummary> find(UUID userId, UUID id) {
    return queries.find(userId, id);
  }

  List<EntrySummary> list(
      UUID userId, java.time.LocalDate from, java.time.LocalDate to, UUID accountId) {
    return queries.list(userId, from, to, accountId);
  }

  private SaveResult saved(UUID userId, UUID id) {
    return queries
        .find(userId, id)
        .<SaveResult>map(SaveResult.Saved::new)
        .orElse(SaveResult.Rejected.ENTRY_NOT_FOUND);
  }

  private Optional<SaveResult.Rejected> checkReferences(
      UUID userId, EntryInput input, boolean allowArchivedAccount) {
    if (input.accountId() == null) {
      return Optional.of(SaveResult.Rejected.ACCOUNT_NOT_FOUND);
    }
    Optional<Boolean> archived = queries.findAccountArchived(userId, input.accountId());
    if (archived.isEmpty()) {
      return Optional.of(SaveResult.Rejected.ACCOUNT_NOT_FOUND);
    }
    if (archived.get() && !allowArchivedAccount) {
      return Optional.of(SaveResult.Rejected.ACCOUNT_ARCHIVED);
    }
    if (input.categoryId() == null
        || !queries.categoryExists(userId, input.categoryId(), input.type())) {
      return Optional.of(SaveResult.Rejected.CATEGORY_NOT_FOUND);
    }
    return Optional.empty();
  }

  // A FK composta é a rede de segurança: conta ou categoria de outro usuário (ou categoria de
  // outro tipo) que passou pela conferência acima ainda é recusada pelo banco. O detalhe do
  // Postgres não é logado.
  private static Optional<SaveResult.Rejected> rejectionOf(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        if (ACCOUNT_FK.equals(violation.getConstraintName())) {
          return Optional.of(SaveResult.Rejected.ACCOUNT_NOT_FOUND);
        }
        if (CATEGORY_FK.equals(violation.getConstraintName())) {
          return Optional.of(SaveResult.Rejected.CATEGORY_NOT_FOUND);
        }
      }
    }
    return Optional.empty();
  }
}
