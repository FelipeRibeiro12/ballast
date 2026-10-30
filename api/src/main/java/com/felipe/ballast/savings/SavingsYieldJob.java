package com.felipe.ballast.savings;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Só existe com ballast.cofrinho.job-enabled. O @Scheduled é lido por qualquer @EnableScheduling do
// contexto, inclusive o das recorrências; com o bean condicional, a flag desliga de verdade.
@Component
@ConditionalOnProperty(name = "ballast.cofrinho.job-enabled", havingValue = "true")
class SavingsYieldJob {

  private final SavingsYieldService yields;

  SavingsYieldJob(SavingsYieldService yields) {
    this.yields = yields;
  }

  @Scheduled(cron = "${ballast.cofrinho.cron}", zone = "America/Sao_Paulo")
  void run() {
    yields.runAllToday();
  }
}
