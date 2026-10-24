package com.felipe.ballast.crypto;

import com.felipe.ballast.limits.UserLimit;
import com.felipe.ballast.limits.UserLimits;
import com.felipe.ballast.web.Constraints;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// Assume entrada já validada na borda. Todo método recebe o dono vindo da sessão.
@Service
class AssetService {

  private static final String ASSET_UNIQUE = "ativo_cripto_usuario_rede_contrato_unico";
  private static final String QUOTE_PK = "cotacao_pkey";
  private static final String QUOTE_ASSET_FK = "cotacao_ativo_do_usuario";
  private static final String MOVEMENT_ASSET_FK = "movimento_cripto_ativo_do_usuario";

  private final CryptoQueries queries;
  private final WriteTransaction transaction;
  private final UserLimits limits;

  AssetService(CryptoQueries queries, WriteTransaction transaction, UserLimits limits) {
    this.limits = limits;
    this.queries = queries;
    this.transaction = transaction;
  }

  sealed interface CreateResult {
    record Created(CryptoQueries.Asset asset) implements CreateResult {}

    enum Rejected implements CreateResult {
      INVALID_CONTRACT,
      DUPLICATE
    }
  }

  enum QuoteResult {
    SAVED,
    ASSET_NOT_FOUND,
    DUPLICATE
  }

  sealed interface UpdateResult {
    record Updated(CryptoQueries.Asset asset) implements UpdateResult {}

    enum Rejected implements UpdateResult {
      NOT_FOUND,
      DECIMALS_TOO_FEW
    }
  }

  enum DeleteResult {
    DELETED,
    NOT_FOUND,
    HAS_MOVEMENTS
  }

  enum QuoteChange {
    DONE,
    NOT_FOUND,
    FROM_PROVIDER
  }

  // Sem SELECT antes: duas requisições iguais passariam juntas, só o unique da V13 segura.
  CreateResult create(
      UUID userId,
      CryptoNetwork network,
      String contract,
      String symbol,
      String name,
      int decimals) {
    String normalized = null;
    if (contract != null) {
      normalized = network.normalizeContract(contract);
      if (normalized == null) {
        return CreateResult.Rejected.INVALID_CONTRACT;
      }
    }
    String stored = normalized;
    try {
      return new CreateResult.Created(
          transaction.execute(
              status -> {
                limits.checkRoom(userId, UserLimit.CRYPTO_ASSETS);
                return queries.insertAsset(userId, network, stored, symbol, name, decimals);
              }));
    } catch (DataIntegrityViolationException e) {
      if (Constraints.isViolated(e, ASSET_UNIQUE)) {
        return CreateResult.Rejected.DUPLICATE;
      }
      throw e;
    }
  }

  List<CryptoQueries.Asset> list(UUID userId) {
    return queries.listAssets(userId);
  }

  // A conferência do dono dá o 404; a FK composta da V13 segura o resto.
  QuoteResult quote(UUID userId, UUID assetId, Instant moment, BigDecimal price) {
    try {
      return transaction.execute(
          status -> {
            if (queries.findAssetDecimals(userId, assetId).isEmpty()) {
              return QuoteResult.ASSET_NOT_FOUND;
            }
            limits.checkRoom(userId, UserLimit.QUOTES);
            queries.insertQuote(userId, assetId, moment, price);
            return QuoteResult.SAVED;
          });
    } catch (DataIntegrityViolationException e) {
      if (Constraints.isViolated(e, QUOTE_PK)) {
        return QuoteResult.DUPLICATE;
      }
      if (Constraints.isViolated(e, QUOTE_ASSET_FK)) {
        return QuoteResult.ASSET_NOT_FOUND;
      }
      throw e;
    }
  }

  // Menos casas que um movimento já usa deixaria a quantidade dele impossível de digitar de novo.
  UpdateResult update(UUID userId, UUID id, String symbol, String name, int decimals) {
    return transaction.execute(
        status -> {
          if (queries.findAssetDecimals(userId, id).isEmpty()) {
            return UpdateResult.Rejected.NOT_FOUND;
          }
          if (queries.hasMovementFinerThan(userId, id, decimals)) {
            return UpdateResult.Rejected.DECIMALS_TOO_FEW;
          }
          return queries
              .updateAsset(userId, id, symbol, name, decimals)
              .<UpdateResult>map(UpdateResult.Updated::new)
              .orElse(UpdateResult.Rejected.NOT_FOUND);
        });
  }

  // As cotações vão junto, ativo com movimento não. A FK dos movimentos fica como rede de
  // segurança para o movimento que entra entre a conferência e o DELETE.
  DeleteResult delete(UUID userId, UUID id) {
    try {
      return transaction.execute(
          status -> {
            if (queries.findAssetDecimals(userId, id).isEmpty()) {
              return DeleteResult.NOT_FOUND;
            }
            if (queries.hasMovements(userId, id)) {
              return DeleteResult.HAS_MOVEMENTS;
            }
            queries.deleteQuotesOf(userId, id);
            return queries.deleteAsset(userId, id) == 0
                ? DeleteResult.NOT_FOUND
                : DeleteResult.DELETED;
          });
    } catch (DataIntegrityViolationException e) {
      if (Constraints.isViolated(e, MOVEMENT_ASSET_FK)) {
        return DeleteResult.HAS_MOVEMENTS;
      }
      throw e;
    }
  }

  // Só a cotação digitada muda: a do provedor é o que ele publicou naquele instante.
  QuoteChange correctQuote(UUID userId, UUID assetId, Instant moment, BigDecimal price) {
    return transaction.execute(
        status -> {
          Optional<String> source = queries.findQuoteSource(userId, assetId, moment);
          if (source.isEmpty()) {
            return QuoteChange.NOT_FOUND;
          }
          if (!"manual".equals(source.get())) {
            return QuoteChange.FROM_PROVIDER;
          }
          return queries.updateQuotePrice(userId, assetId, moment, price) == 0
              ? QuoteChange.NOT_FOUND
              : QuoteChange.DONE;
        });
  }

  QuoteChange deleteQuote(UUID userId, UUID assetId, Instant moment) {
    return transaction.execute(
        status -> {
          Optional<String> source = queries.findQuoteSource(userId, assetId, moment);
          if (source.isEmpty()) {
            return QuoteChange.NOT_FOUND;
          }
          if (!"manual".equals(source.get())) {
            return QuoteChange.FROM_PROVIDER;
          }
          return queries.deleteQuote(userId, assetId, moment) == 0
              ? QuoteChange.NOT_FOUND
              : QuoteChange.DONE;
        });
  }
}
