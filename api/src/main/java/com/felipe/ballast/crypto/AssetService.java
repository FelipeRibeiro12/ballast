package com.felipe.ballast.crypto;

import com.felipe.ballast.web.Constraints;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// Assume entrada já validada na borda. Todo método recebe o dono vindo da sessão.
@Service
class AssetService {

  private static final String ASSET_UNIQUE = "ativo_cripto_usuario_rede_contrato_unico";
  private static final String QUOTE_PK = "cotacao_pkey";
  private static final String QUOTE_ASSET_FK = "cotacao_ativo_do_usuario";

  private final CryptoQueries queries;
  private final WriteTransaction transaction;

  AssetService(CryptoQueries queries, WriteTransaction transaction) {
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
              status -> queries.insertAsset(userId, network, stored, symbol, name, decimals)));
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
}
