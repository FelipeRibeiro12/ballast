package com.felipe.ballast.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Primeira rodada pouco depois de subir e depois a cada intervalo (ADR-040, ADR-041). Só existe
// com ballast.cotacoes.job-enabled; sem a chave da CoinGecko ele não chama nada.
@Component
@ConditionalOnProperty(name = "ballast.cotacoes.job-enabled", havingValue = "true")
class QuoteJob {

  private static final Logger log = LoggerFactory.getLogger(QuoteJob.class);

  private final QuoteUpdater updater;
  private final QuoteBackfill backfill;
  private final UsdcBrlUpdater usdc;
  private final boolean enabled;

  QuoteJob(
      QuoteUpdater updater,
      QuoteBackfill backfill,
      UsdcBrlUpdater usdc,
      QuotesProperties properties) {
    this.updater = updater;
    this.backfill = backfill;
    this.usdc = usdc;
    this.enabled = properties.hasKey();
    if (!enabled) {
      log.warn("cotação automática desligada: COINGECKO_API_KEY vazia");
    }
  }

  @Scheduled(initialDelayString = "PT30S", fixedDelayString = "${ballast.cotacoes.interval}")
  void tick() {
    // A cotação primeiro: o histórico só enche o que vem antes dela.
    if (enabled) {
      updater.update();
      backfill.backfill();
      // Cada etapa guarda a própria falha: o USDC fora do ar não para a cotação dos ativos.
      usdc.update();
      usdc.backfill();
    }
  }
}
