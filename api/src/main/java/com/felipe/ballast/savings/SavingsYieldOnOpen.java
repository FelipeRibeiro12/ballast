package com.felipe.ballast.savings;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Lança o rendimento do dia na primeira abertura do app, sem esperar o job (ADR-074, emenda).
// O mapa em memória só evita travar a conta a cada GET; o marcador do banco segue sendo a
// garantia de que o dia não duplica, mesmo se a API reiniciar.
@Component
@ConditionalOnProperty(name = "ballast.cofrinho.job-enabled", havingValue = "true")
public class SavingsYieldOnOpen {

  private final SavingsYieldService yields;
  private final Map<UUID, LocalDate> lastRun = new ConcurrentHashMap<>();

  SavingsYieldOnOpen(SavingsYieldService yields) {
    this.yields = yields;
  }

  public void run(UUID userId) {
    LocalDate today = yields.today();
    if (today.equals(lastRun.put(userId, today))) {
      return;
    }
    yields.catchUpUser(userId);
  }
}
