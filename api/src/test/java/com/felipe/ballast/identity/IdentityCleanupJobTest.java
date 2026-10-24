package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class IdentityCleanupJobTest {

  @Autowired IdentityCleanupJob job;
  @Autowired IdentityService identity;
  @Autowired TestClock clock;
  @Autowired JdbcClient jdbc;

  private Instant now;
  private UUID userId;

  @BeforeEach
  void setUp() {
    now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    clock.freeze(now);
    userId =
        identity.register(TestUsers.uniqueEmail(), TestUsers.PASSWORD, null).orElseThrow().id();
  }

  @AfterEach
  void resetClock() {
    clock.reset();
  }

  private UUID session(Instant expiresAt, Instant revokedAt) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO sessao (id, usuario_id, token_hash, expira_em, revogada_em)
            VALUES (:id, :user, decode(md5(random()::text) || md5(random()::text), 'hex'),
                    :expires, :revoked)
            """)
        .param("id", id)
        .param("user", userId)
        .param("expires", expiresAt.atOffset(ZoneOffset.UTC))
        .param("revoked", revokedAt == null ? null : revokedAt.atOffset(ZoneOffset.UTC))
        .update();
    return id;
  }

  private boolean sessionExists(UUID id) {
    return jdbc.sql("SELECT count(*) FROM sessao WHERE id = :id")
            .param("id", id)
            .query(Long.class)
            .single()
        == 1;
  }

  private String attempt(Instant lastFailure) {
    String email = TestUsers.uniqueEmail();
    jdbc.sql(
            "INSERT INTO tentativa_login (email, ip, falhas, ultima_falha_em)"
                + " VALUES (:email, '127.0.0.1', 7, :at)")
        .param("email", email)
        .param("at", lastFailure.atOffset(ZoneOffset.UTC))
        .update();
    return email;
  }

  private boolean attemptExists(String email) {
    return jdbc.sql("SELECT count(*) FROM tentativa_login WHERE email = :email")
            .param("email", email)
            .query(Long.class)
            .single()
        == 1;
  }

  @Test
  void deletesExpiredAndRevokedSessionsAndKeepsActiveOnes() {
    UUID expired = session(now.minusSeconds(1), null);
    UUID revoked = session(now.plus(Duration.ofDays(3)), now.minus(Duration.ofHours(1)));
    UUID active = session(now.plus(Duration.ofDays(3)), null);

    job.run();

    assertThat(sessionExists(expired)).isFalse();
    assertThat(sessionExists(revoked)).isFalse();
    assertThat(sessionExists(active)).isTrue();
  }

  // Um dia é muito mais que o atraso máximo de 15 min: a linha só serve para o próximo atraso.
  @Test
  void deletesLoginAttemptsWithoutFailureForADay() {
    String old = attempt(now.minus(Duration.ofDays(1)).minusSeconds(1));
    String recent = attempt(now.minus(Duration.ofHours(23)));

    job.run();

    assertThat(attemptExists(old)).isFalse();
    assertThat(attemptExists(recent)).isTrue();
  }

  @Test
  void deletesExpiredMfaChallengesAndKeepsLiveOnes() {
    UUID expired = challenge(now.minusSeconds(1));
    UUID live = challenge(now.plusSeconds(60));

    job.run();

    assertThat(challengeExists(expired)).isFalse();
    assertThat(challengeExists(live)).isTrue();
  }

  private UUID challenge(Instant expiresAt) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO desafio_mfa (id, usuario_id, token_hash, expira_em)
            VALUES (:id, :user, decode(md5(random()::text) || md5(random()::text), 'hex'), :expires)
            """)
        .param("id", id)
        .param("user", userId)
        .param("expires", expiresAt.atOffset(ZoneOffset.UTC))
        .update();
    return id;
  }

  private boolean challengeExists(UUID id) {
    return jdbc.sql("SELECT count(*) FROM desafio_mfa WHERE id = :id")
            .param("id", id)
            .query(Long.class)
            .single()
        == 1;
  }
}
