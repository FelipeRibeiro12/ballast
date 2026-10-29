package com.felipe.ballast.limits;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// Teto por usuário (ADR-055). Chamar sempre de dentro da transação da escrita, antes de gravar.
@Component
public class UserLimits {

  private final JdbcClient jdbc;
  private final LimitsProperties properties;

  public UserLimits(JdbcClient jdbc, LimitsProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
  }

  // A trava consultiva é por usuário e por tipo de teto e vale até o commit: as criações
  // simultâneas do mesmo usuário entram uma por vez, então a contagem de uma já enxerga a linha da
  // anterior. Sem ela, duas requisições contariam a mesma última vaga. O tipo entra na chave para
  // contas e movimentos, por exemplo, não esperarem um pelo outro. Nenhuma outra trava é tomada
  // antes desta, então ela não fecha ciclo com as de linha.
  public void checkRoom(UUID userId, UserLimit limit) {
    jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", userId + ":" + limit.name())
        .query()
        .singleRow();
    long current = jdbc.sql(limit.countSql).param("userId", userId).query(Long.class).single();
    if (current >= limit.max.applyAsInt(properties)) {
      throw new LimitReachedException(limit);
    }
  }

  // Leitura sem trava, só para recusar cedo uma requisição cara. Não garante nada sob corrida:
  // quem grava confere de novo com checkRoom, dentro da transação.
  public void checkRoomEarly(UUID userId, UserLimit limit) {
    long current = jdbc.sql(limit.countSql).param("userId", userId).query(Long.class).single();
    if (current >= limit.max.applyAsInt(properties)) {
      throw new LimitReachedException(limit);
    }
  }

  public Duration accountDeleteTimeout() {
    return properties.accountDeleteTimeout();
  }
}
