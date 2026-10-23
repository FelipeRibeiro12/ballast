package com.felipe.ballast.crypto;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// Busca uma vez por ativo o preço diário do último ano e grava só os dias antes da primeira
// cotação de cada dono (ADR-041). Depois disso a série segue com o job da F14.
@Component
public class QuoteBackfill {

  private static final Logger log = LoggerFactory.getLogger(QuoteBackfill.class);

  // O Demo recusa pedido que passa de 365 dias para trás; um dia de folga.
  private static final Duration WINDOW = Duration.ofDays(364);

  // Com os até 6 pedidos da cotação na mesma rodada ficam 16 no minuto, abaixo dos 30 do Demo.
  private static final int ASSETS_PER_ROUND = 10;

  private final ReentrantLock running = new ReentrantLock();
  private final QuoteSource source;
  private final CryptoQueries queries;
  private final TransactionTemplate transaction;
  private final Clock clock;

  QuoteBackfill(
      QuoteSource source, CryptoQueries queries, TransactionTemplate transaction, Clock clock) {
    this.source = source;
    this.queries = queries;
    this.transaction = transaction;
    this.clock = clock;
  }

  public void backfill() {
    if (!running.tryLock()) {
      return;
    }
    try {
      Instant now = clock.instant();
      for (AssetRef asset : queries.pendingHistoryRefs(ASSETS_PER_ROUND)) {
        List<ProviderQuote> history = source.history(asset, now.minus(WINDOW), now);
        int stored = 0;
        for (CryptoQueries.PendingHistory pending : queries.pendingHistoryAssets(asset)) {
          stored += transaction.execute(status -> store(pending, history, now));
        }
        log.info("histórico de cotação: rede={}, {} cotações novas", asset.network(), stored);
      }
    } catch (RuntimeException e) {
      // A fonte fora ou o limite do plano: a rodada para e o que faltou fica para a próxima. Sem a
      // mensagem da exceção, que pode trazer a URL com o contrato.
      log.warn("histórico de cotação não buscado: erro={}", e.getClass().getSimpleName());
    } finally {
      running.unlock();
    }
  }

  // Na mesma transação que marca o ativo: se cair no meio, ele volta pendente sem buraco na série.
  private int store(
      CryptoQueries.PendingHistory pending, List<ProviderQuote> history, Instant now) {
    Instant limit =
        queries
            .firstQuoteMoment(pending.userId(), pending.assetId())
            .filter(first -> first.isBefore(now))
            .orElse(now);
    int stored = 0;
    for (ProviderQuote quote : history) {
      Optional<BigDecimal> price = QuoteUpdater.storablePrice(quote.priceUsd());
      if (price.isPresent() && quote.updatedAt().isBefore(limit)) {
        stored +=
            queries.insertHistoryQuote(
                pending.userId(), pending.assetId(), quote.updatedAt(), price.get());
      }
    }
    queries.markHistoryDone(pending.userId(), pending.assetId(), now);
    return stored;
  }
}
