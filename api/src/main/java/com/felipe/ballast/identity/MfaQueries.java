package com.felipe.ballast.identity;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Toda consulta é escopada pelo usuário da sessão ou do desafio. A hora vem do relógio da
// aplicação, como em LoginAttempts.
@Repository
class MfaQueries {

  record Row(byte[] sealedSecret, boolean active) {}

  record Delay(Duration remaining) {}

  private final JdbcClient jdbc;

  MfaQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<Row> find(UUID userId) {
    return jdbc.sql("SELECT segredo_cifrado, ativado_em FROM mfa WHERE usuario_id = :user")
        .param("user", userId)
        .query(
            (rs, i) -> new Row(rs.getBytes("segredo_cifrado"), rs.getObject("ativado_em") != null))
        .optional();
  }

  // Troca o segredo pendente, nunca o ativo: 0 quando o MFA já está ligado.
  int savePending(UUID userId, byte[] sealedSecret) {
    return jdbc.sql(
            """
            INSERT INTO mfa (usuario_id, segredo_cifrado) VALUES (:user, :secret)
            ON CONFLICT (usuario_id) DO UPDATE
            SET segredo_cifrado = EXCLUDED.segredo_cifrado, ultimo_passo = NULL
            WHERE mfa.ativado_em IS NULL
            """)
        .param("user", userId)
        .param("secret", sealedSecret)
        .update();
  }

  // O mesmo UPSERT condicional de LoginAttempts.reserve, na linha do MFA do usuário: tentativas
  // simultâneas contam uma a uma, e dentro do atraso nada muda (vazio).
  OptionalInt reserve(UUID userId, Instant now) {
    return jdbc.sql(
            """
            UPDATE mfa SET falhas = falhas + 1, ultima_falha_em = :now
            WHERE usuario_id = :user
              AND (falhas <= :free
                   OR ultima_falha_em
                      + make_interval(secs => least(power(2, least(falhas - :free, :maxExponent)), :maxSeconds))
                      <= :now)
            RETURNING falhas
            """)
        .param("user", userId)
        .param("now", utc(now))
        .param("free", LoginAttempts.FREE_FAILURES)
        .param("maxExponent", LoginAttempts.MAX_EXPONENT)
        .param("maxSeconds", (int) LoginAttempts.MAX_DELAY.toSeconds())
        .query(Integer.class)
        .optional()
        .map(OptionalInt::of)
        .orElseGet(OptionalInt::empty);
  }

  Optional<Delay> findDelay(UUID userId, Instant now) {
    return jdbc.sql("SELECT falhas, ultima_falha_em FROM mfa WHERE usuario_id = :user")
        .param("user", userId)
        .query(
            (rs, i) -> {
              OffsetDateTime last = rs.getObject("ultima_falha_em", OffsetDateTime.class);
              if (last == null) {
                return new Delay(Duration.ZERO);
              }
              Instant until = last.toInstant().plus(LoginAttempts.delayAfter(rs.getInt("falhas")));
              return new Delay(Duration.between(now, until));
            })
        .optional();
  }

  void clearFailures(UUID userId) {
    jdbc.sql("UPDATE mfa SET falhas = 0, ultima_falha_em = NULL WHERE usuario_id = :user")
        .param("user", userId)
        .update();
  }

  // Só passo mais novo que o último aceito: o mesmo código não entra duas vezes, nem um código
  // anterior que ainda está na janela de tolerância.
  boolean acceptStep(UUID userId, long step) {
    return jdbc.sql(
                """
                UPDATE mfa SET ultimo_passo = :step
                WHERE usuario_id = :user AND ativado_em IS NOT NULL
                  AND (ultimo_passo IS NULL OR ultimo_passo < :step)
                """)
            .param("user", userId)
            .param("step", step)
            .update()
        == 1;
  }

  boolean activate(UUID userId, long step, Instant now) {
    return jdbc.sql(
                """
                UPDATE mfa SET ativado_em = :now, ultimo_passo = :step
                WHERE usuario_id = :user AND ativado_em IS NULL
                """)
            .param("user", userId)
            .param("step", step)
            .param("now", utc(now))
            .update()
        == 1;
  }

  void replaceRecoveryCodes(UUID userId, List<byte[]> hashes) {
    jdbc.sql("DELETE FROM mfa_recuperacao WHERE usuario_id = :user").param("user", userId).update();
    for (byte[] hash : hashes) {
      jdbc.sql(
              "INSERT INTO mfa_recuperacao (id, usuario_id, codigo_hash) VALUES (:id, :user, :hash)")
          .param("id", newId())
          .param("user", userId)
          .param("hash", hash)
          .update();
    }
  }

  boolean consumeRecoveryCode(UUID userId, byte[] hash, Instant now) {
    return jdbc.sql(
                """
                UPDATE mfa_recuperacao SET usado_em = :now
                WHERE usuario_id = :user AND codigo_hash = :hash AND usado_em IS NULL
                """)
            .param("user", userId)
            .param("hash", hash)
            .param("now", utc(now))
            .update()
        == 1;
  }

  int remainingRecoveryCodes(UUID userId) {
    return jdbc.sql(
            "SELECT count(*) FROM mfa_recuperacao WHERE usuario_id = :user AND usado_em IS NULL")
        .param("user", userId)
        .query(Integer.class)
        .single();
  }

  // Os códigos de recuperação vão junto, pela FK com CASCADE.
  boolean delete(UUID userId) {
    jdbc.sql("DELETE FROM desafio_mfa WHERE usuario_id = :user").param("user", userId).update();
    return jdbc.sql("DELETE FROM mfa WHERE usuario_id = :user AND ativado_em IS NOT NULL")
            .param("user", userId)
            .update()
        == 1;
  }

  // Um desafio por usuário: o login novo invalida o anterior.
  void createChallenge(UUID userId, byte[] tokenHash, Instant expiresAt) {
    jdbc.sql("DELETE FROM desafio_mfa WHERE usuario_id = :user").param("user", userId).update();
    jdbc.sql(
            """
            INSERT INTO desafio_mfa (id, usuario_id, token_hash, expira_em)
            VALUES (:id, :user, :hash, :expires)
            """)
        .param("id", newId())
        .param("user", userId)
        .param("hash", tokenHash)
        .param("expires", utc(expiresAt))
        .update();
  }

  Optional<UUID> findChallenge(byte[] tokenHash, Instant now) {
    return jdbc.sql(
            "SELECT usuario_id FROM desafio_mfa WHERE token_hash = :hash AND expira_em > :now")
        .param("hash", tokenHash)
        .param("now", utc(now))
        .query(UUID.class)
        .optional();
  }

  // O DELETE é o que garante uma sessão por desafio: de duas respostas certas ao mesmo tempo,
  // só uma apaga a linha.
  boolean consumeChallenge(byte[] tokenHash, UUID userId) {
    return jdbc.sql("DELETE FROM desafio_mfa WHERE token_hash = :hash AND usuario_id = :user")
            .param("hash", tokenHash)
            .param("user", userId)
            .update()
        == 1;
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static UUID newId() {
    // O gerador do Hibernate não usa a sessão; é o mesmo dos @UuidGenerator das entidades.
    return UuidVersion7Strategy.INSTANCE.generateUuid(null);
  }
}
