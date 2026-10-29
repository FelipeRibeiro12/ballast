package com.felipe.ballast.wallets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Lê todas as carteiras a cada intervalo (6 h), uma por vez e com pausa entre elas (ADR-071). Só
// existe com ballast.carteiras.job-enabled; sem a chave da Alchemy não chama nada. Falha numa
// carteira não para as outras, e o log leva só os ids.
@Component
@ConditionalOnProperty(name = "ballast.carteiras.job-enabled", havingValue = "true")
class WalletJob {

  private static final Logger log = LoggerFactory.getLogger(WalletJob.class);

  private final WalletService service;
  private final WalletIndexer indexer;
  private final WalletsProperties properties;

  WalletJob(WalletService service, WalletIndexer indexer, WalletsProperties properties) {
    this.service = service;
    this.indexer = indexer;
    this.properties = properties;
    if (!indexer.isConfigured()) {
      log.warn("leitura de carteiras desligada: ALCHEMY_API_KEY vazia");
    }
  }

  @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${ballast.carteiras.interval}")
  void tick() {
    if (!indexer.isConfigured()) {
      return;
    }
    boolean first = true;
    for (WalletQueries.Ref ref : service.activeRefs()) {
      if (!first && !pause()) {
        return;
      }
      first = false;
      try {
        service.sync(ref.userId(), ref.walletId());
      } catch (RuntimeException e) {
        // Só a classe: a mensagem de uma exceção de banco pode trazer o valor da linha.
        log.error(
            "carteira não sincronizada: carteira_id={} tipo={}",
            ref.walletId(),
            e.getClass().getSimpleName());
      }
    }
  }

  // Falso quando a thread foi interrompida (desligamento): para o laço.
  private boolean pause() {
    try {
      Thread.sleep(properties.pause());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
