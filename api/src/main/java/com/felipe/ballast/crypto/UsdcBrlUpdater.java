package com.felipe.ballast.crypto;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Guarda o USDC em reais, que converte toda a cripto de dólar para real (ADR-077). Só o job
// chama: nenhuma requisição espera a CoinGecko, e com ela fora valem a última leitura e a data
// dela.
@Component
public class UsdcBrlUpdater {

  private static final Logger log = LoggerFactory.getLogger(UsdcBrlUpdater.class);

  // O Demo recusa pedido que passa de 365 dias para trás; um dia de folga (ADR-041).
  private static final Duration WINDOW = Duration.ofDays(364);

  // Se já há leitura com mais de 300 dias, o histórico foi buscado: não gasta pedido de novo a cada
  // rodada. Abaixo de 364 para tolerar o primeiro ponto do provedor cair uns dias depois do pedido.
  private static final Duration ALREADY_FILLED = Duration.ofDays(300);

  // Mesma folga do QuoteUpdater para o relógio do provedor adiantado.
  private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

  private final ReentrantLock running = new ReentrantLock();
  private final QuoteSource source;
  private final CryptoQueries queries;
  private final Clock clock;

  UsdcBrlUpdater(QuoteSource source, CryptoQueries queries, Clock clock) {
    this.source = source;
    this.queries = queries;
    this.clock = clock;
  }

  public void update() {
    if (!running.tryLock()) {
      return;
    }
    try {
      Optional<UsdcQuote> quote = source.usdcBrl();
      if (quote.isEmpty()) {
        return;
      }
      Optional<BigDecimal> price = QuoteUpdater.storablePrice(quote.get().priceBrl());
      if (price.isEmpty() || quote.get().updatedAt().isAfter(clock.instant().plus(CLOCK_SKEW))) {
        log.warn("cotação do USDC fora da faixa ignorada");
        return;
      }
      int stored = queries.insertUsdcBrl(quote.get().updatedAt(), price.get());
      log.info("cotação do USDC: {} leituras novas", stored);
    } catch (RuntimeException e) {
      // Sem a mensagem da exceção: ela pode trazer a URL.
      log.warn("cotação do USDC não atualizada: erro={}", e.getClass().getSimpleName());
    } finally {
      running.unlock();
    }
  }

  public void backfill() {
    if (!running.tryLock()) {
      return;
    }
    try {
      Instant now = clock.instant();
      if (queries.hasUsdcBrlBefore(now.minus(ALREADY_FILLED))) {
        return;
      }
      int stored = 0;
      for (UsdcQuote point : source.usdcBrlHistory(now.minus(WINDOW), now)) {
        Optional<BigDecimal> price = QuoteUpdater.storablePrice(point.priceBrl());
        if (price.isPresent() && !point.updatedAt().isAfter(now.plus(CLOCK_SKEW))) {
          stored += queries.insertUsdcBrl(point.updatedAt(), price.get());
        }
      }
      log.info("histórico do USDC: {} leituras novas", stored);
    } catch (RuntimeException e) {
      log.warn("histórico do USDC não buscado: erro={}", e.getClass().getSimpleName());
    } finally {
      running.unlock();
    }
  }
}
