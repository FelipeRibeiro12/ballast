package com.felipe.ballast.categories;

import com.felipe.ballast.limits.UserLimit;
import com.felipe.ballast.limits.UserLimits;
import com.felipe.ballast.web.WriteTransaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
class CategoryService {

  private static final String UNIQUE_NAME = "categoria_usuario_pai_nome_unico";

  sealed interface Result {
    record Done(UUID id) implements Result {}

    record NotFound() implements Result {}

    record Protected() implements Result {}

    record DuplicateName() implements Result {}

    record Listed() implements Result {}

    record HasChildren() implements Result {}

    record Changed() implements Result {}
  }

  private final CategoryQueries queries;
  private final WriteTransaction transaction;
  private final UserLimits limits;

  CategoryService(CategoryQueries queries, WriteTransaction transaction, UserLimits limits) {
    this.queries = queries;
    this.transaction = transaction;
    this.limits = limits;
  }

  Result create(UUID userId, String name, CategoryType type) {
    try {
      return transaction.execute(
          status -> {
            limits.checkRoom(userId, UserLimit.CATEGORIES);
            return new Result.Done(queries.insert(userId, name.strip(), type.value()));
          });
    } catch (DataIntegrityViolationException e) {
      return duplicateOrRethrow(e);
    }
  }

  Result rename(UUID userId, UUID id, String name) {
    try {
      return transaction.execute(
          status -> {
            Optional<CategoryQueries.Row> row = queries.find(userId, id);
            if (row.isEmpty()) {
              return new Result.NotFound();
            }
            if (row.get().isProtected()) {
              return new Result.Protected();
            }
            queries.rename(userId, id, name.strip());
            return new Result.Done(id);
          });
    } catch (DataIntegrityViolationException e) {
      return duplicateOrRethrow(e);
    }
  }

  // Apagar move lançamentos e recorrências para a Outros do mesmo tipo e soma o orçamento nela.
  Result delete(UUID userId, UUID id) {
    try {
      return deleteLocked(userId, id);
    } catch (DataIntegrityViolationException e) {
      // Um lançamento entrou na categoria entre a mudança e o DELETE: nada foi apagado.
      return new Result.Changed();
    }
  }

  private Result deleteLocked(UUID userId, UUID id) {
    return transaction.execute(
        status -> {
          Optional<CategoryQueries.Row> found = queries.find(userId, id);
          if (found.isEmpty()) {
            return new Result.NotFound();
          }
          CategoryQueries.Row row = found.get();
          if (row.isProtected()) {
            return new Result.Protected();
          }
          if (row.hasChildren()) {
            return new Result.HasChildren();
          }
          if (queries.isListedInBenefitAccount(userId, id)) {
            return new Result.Listed();
          }
          UUID fallback = queries.findFallback(userId, row.type());
          queries.moveEntries(userId, id, fallback);
          if ("despesa".equals(row.type())) {
            queries.moveBudgets(userId, id, fallback);
          }
          queries.delete(userId, id);
          return new Result.Done(id);
        });
  }

  private static Result duplicateOrRethrow(DataIntegrityViolationException e) {
    if (e.getMessage() != null && e.getMessage().contains(UNIQUE_NAME)) {
      return new Result.DuplicateName();
    }
    throw e;
  }
}
