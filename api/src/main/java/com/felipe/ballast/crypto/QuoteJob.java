package com.felipe.ballast.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Primeira rodada pouco depois de subir e depois a cada intervalo (ADR-040). Só existe com
// ballast.cotacoes.job-enabled; sem a chave da CoinGecko ele não chama nada.
@Component
@ConditionalOnProperty(name = "ballast.cotacoes.job-enabled", havingValue = "true")
class QuoteJob {

  private static final Logger log = LoggerFactory.getLogger(QuoteJob.class);

  private final QuoteUpdater updater;
  private final boolean enabled;

  QuoteJob(QuoteUpdater updater, QuotesProperties properties) {
    this.updater = updater;
    this.enabled = properties.hasKey();
    if (!enabled) {
      log.warn("cotação automática desligada: COINGECKO_API_KEY vazia");
    }
  }

  @Scheduled(initialDelayString = "PT30S", fixedDelayString = "${ballast.cotacoes.interval}")
  void tick() {
    if (enabled) {
      updater.update();
    }
  }
}
