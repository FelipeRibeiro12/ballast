package com.felipe.ballast.crypto;

import com.felipe.ballast.fixedincome.IndexUpdater;
import com.felipe.ballast.limits.UserLimit;
import com.felipe.ballast.limits.UserLimits;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;

// Assume entrada já validada na borda. Toda escrita trava a conta antes de conferir a posição
// (ADR-035), na mesma ordem em criar e apagar, então duas escritas da conta não se cruzam.
@Service
class MovementService {

  private final CryptoQueries queries;
  private final WriteTransaction transaction;
  private final IndexUpdater indices;
  private final UserLimits limits;

  MovementService(
      CryptoQueries queries,
      WriteTransaction transaction,
      IndexUpdater indices,
      UserLimits limits) {
    this.limits = limits;
    this.queries = queries;
    this.transaction = transaction;
    this.indices = indices;
  }

  // accountId e assetId chegam nulos quando o texto do cliente não era UUID.
  record Input(
      UUID accountId,
      UUID assetId,
      MovementType type,
      BigDecimal quantity,
      LocalDate date,
      BigDecimal costUsd) {}

  sealed interface CreateResult {
    record Created(CryptoQueries.Movement movement) implements CreateResult {}

    enum Rejected implements CreateResult {
      ACCOUNT_NOT_FOUND,
      NOT_CRYPTO,
      ACCOUNT_ARCHIVED,
      ASSET_NOT_FOUND,
      TOO_MANY_DECIMALS,
      SALE_TOO_BIG
    }
  }

  enum DeleteResult {
    DELETED,
    NOT_FOUND,
    WOULD_GO_NEGATIVE
  }

  // Só movimento antes da primeira PTAX guardada pede busca (ADR-038): o resto o job diário traz.
  // A busca roda em segundo plano, então a resposta não traz o custo em reais nesse caso e a
  // leitura seguinte traz (ADR-058).
  CreateResult create(UUID userId, Input input) {
    CreateResult result = transaction.execute(status -> createLocked(status, userId, input));
    if (result instanceof CreateResult.Created
        && queries.firstDollarDate().map(first -> input.date().isBefore(first)).orElse(true)) {
      indices.requestFrom(input.date());
    }
    return result;
  }

  DeleteResult delete(UUID userId, UUID id) {
    return transaction.execute(status -> deleteLocked(status, userId, id));
  }

  record Page(List<CryptoQueries.Movement> items, boolean more) {}

  // Busca um a mais que o limite só para saber se há próxima página.
  Page list(UUID userId, UUID accountId, UUID assetId, MovementCursor after, int limit) {
    List<CryptoQueries.Movement> found =
        queries.listMovements(userId, accountId, assetId, after, limit + 1);
    boolean more = found.size() > limit;
    return new Page(more ? found.subList(0, limit) : found, more);
  }

  private CreateResult createLocked(TransactionStatus status, UUID userId, Input input) {
    limits.checkRoom(userId, UserLimit.CRYPTO_MOVEMENTS);
    if (input.accountId() == null) {
      return CreateResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    Optional<CryptoQueries.LockedAccount> account = queries.lockAccount(userId, input.accountId());
    if (account.isEmpty()) {
      return CreateResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    if (!account.get().crypto()) {
      return CreateResult.Rejected.NOT_CRYPTO;
    }
    if (account.get().archived()) {
      return CreateResult.Rejected.ACCOUNT_ARCHIVED;
    }
    Optional<Integer> decimals =
        input.assetId() == null
            ? Optional.empty()
            : queries.findAssetDecimals(userId, input.assetId());
    if (decimals.isEmpty()) {
      return CreateResult.Rejected.ASSET_NOT_FOUND;
    }
    // Arredondar a quantidade inventaria fração do ativo; recusar devolve a decisão ao usuário.
    if (Decimals.significantScale(input.quantity()) > decimals.get()) {
      return CreateResult.Rejected.TOO_MANY_DECIMALS;
    }
    CryptoQueries.Movement saved =
        queries.insertMovement(
            userId,
            input.accountId(),
            input.assetId(),
            input.type(),
            input.quantity(),
            input.date(),
            input.costUsd());
    // Só a venda pode baixar a posição. Conferir depois de gravar cobre também a venda retroativa
    // que tira de uma venda posterior a quantidade que ela já usava.
    if (input.type() == MovementType.SELL
        && !queries.positionNeverNegative(userId, input.accountId(), input.assetId())) {
      status.setRollbackOnly();
      return CreateResult.Rejected.SALE_TOO_BIG;
    }
    return new CreateResult.Created(saved);
  }

  // O movimento é lido sem trava só para achar a conta; a trava da conta vem antes do DELETE, como
  // na criação. Se outro DELETE passou no meio, o DELETE daqui apaga zero linhas.
  private DeleteResult deleteLocked(TransactionStatus status, UUID userId, UUID id) {
    Optional<CryptoQueries.Movement> movement = queries.findMovement(userId, id);
    if (movement.isEmpty()) {
      return DeleteResult.NOT_FOUND;
    }
    // O retorno não importa aqui: a conta do movimento é do dono, e se o movimento sumiu no meio o
    // DELETE abaixo apaga zero linhas.
    queries.lockAccount(userId, movement.get().accountId());
    if (queries.deleteMovement(userId, id) == 0) {
      return DeleteResult.NOT_FOUND;
    }
    if (movement.get().type() == MovementType.BUY
        && !queries.positionNeverNegative(
            userId, movement.get().accountId(), movement.get().assetId())) {
      status.setRollbackOnly();
      return DeleteResult.WOULD_GO_NEGATIVE;
    }
    return DeleteResult.DELETED;
  }
}
