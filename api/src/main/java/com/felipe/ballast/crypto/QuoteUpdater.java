package com.felipe.ballast.crypto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Busca na fonte a cotação de cada ativo confiável e grava uma linha por ativo de cada usuário
// (ADR-040). Só o job chama: nenhuma requisição espera a CoinGecko, e com ela fora o patrimônio
// segue com a última cotação guardada.
@Component
public class QuoteUpdater {

  private static final Logger log = LoggerFactory.getLogger(QuoteUpdater.class);

  // numeric(20,8): 12 dígitos inteiros.
  private static final BigDecimal MAX_PRICE = new BigDecimal("999999999999.99999999");

  // Folga para o relógio do provedor um pouco adiantado; além disso a cotação seria "do futuro" e
  // venceria a digitada depois dela.
  private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

  private final ReentrantLock running = new ReentrantLock();
  private final QuoteSource source;
  private final CryptoQueries queries;
  private final Clock clock;

  QuoteUpdater(QuoteSource source, CryptoQueries queries, Clock clock) {
    this.source = source;
    this.queries = queries;
    this.clock = clock;
  }

  // Uma por vez: quem chega com outra rodando volta na hora, como no IndexUpdater.
  public void update() {
    if (!running.tryLock()) {
      return;
    }
    try {
      List<AssetRef> assets = queries.trustedAssetRefs();
      if (assets.isEmpty()) {
        return;
      }
      Map<AssetRef, ProviderQuote> quotes = source.fetch(assets);
      Instant latest = clock.instant().plus(CLOCK_SKEW);
      int stored = 0;
      for (Map.Entry<AssetRef, ProviderQuote> entry : quotes.entrySet()) {
        ProviderQuote quote = entry.getValue();
        Optional<BigDecimal> price = storablePrice(quote.priceUsd());
        if (price.isEmpty() || quote.updatedAt().isAfter(latest)) {
          log.warn("cotação automática fora da faixa ignorada: rede={}", entry.getKey().network());
          continue;
        }
        stored += queries.insertProviderQuote(entry.getKey(), quote.updatedAt(), price.get());
      }
      log.info("cotação automática: {} ativos pedidos, {} cotações novas", assets.size(), stored);
    } catch (RuntimeException e) {
      // Sem a mensagem da exceção: ela pode trazer a URL com os contratos.
      log.warn("cotação automática não atualizada: erro={}", e.getClass().getSimpleName());
    } finally {
      running.unlock();
    }
  }

  // Oito casas, o que a coluna guarda. HALF_EVEN pelo mesmo motivo do valor da posição (ADR-035):
  // avaliação sem direção certa para o meio. Vazio quando arredonda para zero ou não cabe.
  static Optional<BigDecimal> storablePrice(BigDecimal priceUsd) {
    BigDecimal price = priceUsd.setScale(8, RoundingMode.HALF_EVEN);
    if (price.signum() <= 0 || price.compareTo(MAX_PRICE) > 0) {
      return Optional.empty();
    }
    return Optional.of(price);
  }
}
