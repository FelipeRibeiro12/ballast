package com.felipe.ballast.fixedincome;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Ao subir e no cron (ADR-037). Só existe com ballast.indices.job-enabled; os testes de integração
// desligam e chamam o IndexUpdater direto.
@Component
@ConditionalOnProperty(name = "ballast.indices.job-enabled", havingValue = "true")
class IndexJob {

  private final IndexUpdater updater;

  IndexJob(IndexUpdater updater) {
    this.updater = updater;
  }

  @EventListener(ApplicationReadyEvent.class)
  void atStartup() {
    updater.update();
  }

  @Scheduled(cron = "${ballast.indices.cron}", zone = "America/Sao_Paulo")
  void daily() {
    updater.update();
  }
}
