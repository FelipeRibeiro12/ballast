package com.felipe.ballast.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Sem isto sessao e tentativa_login só crescem: cada e-mail digitado no login vira uma linha,
// exista a conta ou não. O agendamento liga em IdentityCleanupSchedulingConfig; o método run
// existe sempre, para o teste chamar direto.
@Component
class IdentityCleanupJob {

  private static final Logger log = LoggerFactory.getLogger(IdentityCleanupJob.class);

  // Muito acima do atraso máximo de login (15 min). Passado um dia sem falha, o e-mail volta a ter
  // as três tentativas livres.
  static final Duration ATTEMPT_RETENTION = Duration.ofDays(1);

  private final JdbcClient jdbc;
  private final Clock clock;

  IdentityCleanupJob(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  // Sessão revogada não volta a valer; o logout já foi para o log.
  @Scheduled(cron = "${ballast.identity-cleanup.cron}", zone = "America/Sao_Paulo")
  void run() {
    Instant now = clock.instant();
    int sessions =
        jdbc.sql("DELETE FROM sessao WHERE expira_em <= :now OR revogada_em IS NOT NULL")
            .param("now", now.atOffset(ZoneOffset.UTC))
            .update();
    int attempts =
        jdbc.sql("DELETE FROM tentativa_login WHERE ultima_falha_em <= :before")
            .param("before", now.minus(ATTEMPT_RETENTION).atOffset(ZoneOffset.UTC))
            .update();
    int challenges =
        jdbc.sql("DELETE FROM desafio_mfa WHERE expira_em <= :now")
            .param("now", now.atOffset(ZoneOffset.UTC))
            .update();
    log.info(
        "limpeza de identidade: sessoes={} tentativas={} desafios_mfa={}",
        sessions,
        attempts,
        challenges);
  }
}
