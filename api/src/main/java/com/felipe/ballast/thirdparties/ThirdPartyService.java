package com.felipe.ballast.thirdparties;

import com.felipe.ballast.limits.UserLimit;
import com.felipe.ballast.limits.UserLimits;
import com.felipe.ballast.web.Constraints;
import com.felipe.ballast.web.WriteTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
class ThirdPartyService {

  private static final String UNIQUE_NAME = "terceiro_usuario_nome_unico";
  private static final String ENTRY_FK = "terceiro_lancamento_do_usuario";
  private static final String THIRD_PARTY_FK = "terceiro_lancamento_terceiro_do_usuario";

  enum Failure {
    NOT_FOUND,
    DUPLICATE_NAME,
    HAS_ENTRIES,
    ARCHIVED,
    TRANSFER_ENTRY
  }

  sealed interface Result<T> {
    record Ok<T>(T value) implements Result<T> {}

    record Failed<T>(Failure failure) implements Result<T> {}
  }

  private final ThirdPartyQueries queries;
  private final WriteTransaction transaction;
  private final UserLimits limits;

  ThirdPartyService(ThirdPartyQueries queries, WriteTransaction transaction, UserLimits limits) {
    this.queries = queries;
    this.transaction = transaction;
    this.limits = limits;
  }

  List<ThirdPartyQueries.ThirdParty> list(UUID userId) {
    return queries.list(userId);
  }

  Result<ThirdPartyQueries.ThirdParty> create(UUID userId, String name) {
    try {
      return transaction.execute(
          status -> {
            limits.checkRoom(userId, UserLimit.THIRD_PARTIES);
            UUID id = queries.insert(userId, name.strip());
            return new Result.Ok<>(queries.find(userId, id).orElseThrow());
          });
    } catch (DataIntegrityViolationException e) {
      return duplicateOrRethrow(e);
    }
  }

  Result<ThirdPartyQueries.ThirdParty> update(UUID userId, UUID id, String name, boolean archived) {
    try {
      return transaction.execute(
          status -> {
            if (queries.update(userId, id, name.strip(), archived) == 0) {
              return new Result.Failed<>(Failure.NOT_FOUND);
            }
            return new Result.Ok<>(queries.find(userId, id).orElseThrow());
          });
    } catch (DataIntegrityViolationException e) {
      return duplicateOrRethrow(e);
    }
  }

  // Terceiro com compra ou reembolso ligado não se apaga: o histórico da dívida some junto.
  // Arquive no lugar.
  Result<Boolean> delete(UUID userId, UUID id) {
    return transaction.execute(
        status -> {
          if (queries.find(userId, id).isEmpty()) {
            return new Result.Failed<>(Failure.NOT_FOUND);
          }
          if (queries.hasEntries(userId, id)) {
            return new Result.Failed<>(Failure.HAS_ENTRIES);
          }
          queries.delete(userId, id);
          return new Result.Ok<>(true);
        });
  }

  Optional<List<ThirdPartyQueries.TaggedEntry>> entries(UUID userId, UUID id) {
    if (queries.find(userId, id).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(queries.entries(userId, id));
  }

  // thirdPartyId nulo solta o vínculo.
  Result<Boolean> setEntryThirdParty(UUID userId, UUID entryId, UUID thirdPartyId) {
    try {
      return setEntryLocked(userId, entryId, thirdPartyId);
    } catch (DataIntegrityViolationException e) {
      return vanishedOrRethrow(e);
    }
  }

  private Result<Boolean> setEntryLocked(UUID userId, UUID entryId, UUID thirdPartyId) {
    return transaction.execute(
        status -> {
          switch (queries.kindOf(userId, entryId)) {
            case NOT_FOUND -> {
              return new Result.Failed<>(Failure.NOT_FOUND);
            }
            case TRANSFER -> {
              return new Result.Failed<>(Failure.TRANSFER_ENTRY);
            }
            case OK -> {}
          }
          if (thirdPartyId == null) {
            queries.untagEntry(userId, entryId);
            return new Result.Ok<>(true);
          }
          Optional<Boolean> archived = queries.lockArchived(userId, thirdPartyId);
          if (archived.isEmpty()) {
            return new Result.Failed<>(Failure.NOT_FOUND);
          }
          if (archived.get()) {
            return new Result.Failed<>(Failure.ARCHIVED);
          }
          queries.tagEntry(userId, entryId, thirdPartyId);
          return new Result.Ok<>(true);
        });
  }

  Result<Boolean> setPurchaseThirdParty(UUID userId, UUID purchaseId, UUID thirdPartyId) {
    try {
      return setPurchaseLocked(userId, purchaseId, thirdPartyId);
    } catch (DataIntegrityViolationException e) {
      return vanishedOrRethrow(e);
    }
  }

  private Result<Boolean> setPurchaseLocked(UUID userId, UUID purchaseId, UUID thirdPartyId) {
    return transaction.execute(
        status -> {
          if (!queries.purchaseExists(userId, purchaseId)) {
            return new Result.Failed<>(Failure.NOT_FOUND);
          }
          if (thirdPartyId == null) {
            queries.untagPurchase(userId, purchaseId);
            return new Result.Ok<>(true);
          }
          Optional<Boolean> archived = queries.lockArchived(userId, thirdPartyId);
          if (archived.isEmpty()) {
            return new Result.Failed<>(Failure.NOT_FOUND);
          }
          if (archived.get()) {
            return new Result.Failed<>(Failure.ARCHIVED);
          }
          queries.tagPurchase(userId, purchaseId, thirdPartyId);
          return new Result.Ok<>(true);
        });
  }

  // O lançamento, a compra ou o terceiro sumiu entre a conferência e a gravação: para o cliente é
  // o mesmo que nunca ter existido. A FK composta já impediu o vínculo com dono errado.
  private static <T> Result<T> vanishedOrRethrow(DataIntegrityViolationException e) {
    if (Constraints.isViolated(e, ENTRY_FK) || Constraints.isViolated(e, THIRD_PARTY_FK)) {
      return new Result.Failed<>(Failure.NOT_FOUND);
    }
    throw e;
  }

  private static <T> Result<T> duplicateOrRethrow(DataIntegrityViolationException e) {
    if (Constraints.isViolated(e, UNIQUE_NAME)) {
      return new Result.Failed<>(Failure.DUPLICATE_NAME);
    }
    throw e;
  }
}
