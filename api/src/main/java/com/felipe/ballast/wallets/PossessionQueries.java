package com.felipe.ballast.wallets;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Toda consulta leva usuario_id; a FK composta da V37 garante o mesmo dono da carteira.
@Repository
class PossessionQueries {

  record Challenge(String nonce, String message, Instant expiresAt) {}

  private final JdbcClient jdbc;

  PossessionQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Grava ou substitui o desafio da carteira. O WHERE do conflito é o limite de frequência: só
  // troca se o desafio atual venceria até latestExpiry, ou seja, se foi emitido há tempo
  // suficiente. A decisão e a escrita são uma instrução, então duas requisições simultâneas não
  // furam o intervalo. Falso quando o limite barrou.
  boolean upsertChallenge(
      UUID userId,
      UUID walletId,
      String nonce,
      String message,
      Instant expiresAt,
      Instant latestExpiry) {
    return jdbc.sql(
                """
                INSERT INTO posse_desafio (carteira_id, usuario_id, nonce, mensagem, expira_em)
                VALUES (:walletId, :userId, :nonce, :message, :expiresAt)
                ON CONFLICT (carteira_id) DO UPDATE
                SET nonce = EXCLUDED.nonce, mensagem = EXCLUDED.mensagem,
                    expira_em = EXCLUDED.expira_em
                WHERE posse_desafio.expira_em <= :latestExpiry
                """)
            .param("walletId", walletId)
            .param("userId", userId)
            .param("nonce", nonce)
            .param("message", message)
            .param("expiresAt", utc(expiresAt))
            .param("latestExpiry", utc(latestExpiry))
            .update()
        > 0;
  }

  // Lê e apaga numa instrução: dois pedidos simultâneos com o mesmo nonce não passam os dois.
  Optional<Challenge> consumeChallenge(UUID userId, UUID walletId) {
    return jdbc.sql(
            """
            DELETE FROM posse_desafio WHERE carteira_id = :walletId AND usuario_id = :userId
            RETURNING nonce, mensagem, expira_em
            """)
        .param("walletId", walletId)
        .param("userId", userId)
        .query(
            (rs, row) ->
                new Challenge(
                    rs.getString("nonce"),
                    rs.getString("mensagem"),
                    rs.getObject("expira_em", OffsetDateTime.class).toInstant()))
        .optional();
  }

  boolean markVerified(UUID userId, UUID walletId, Instant at) {
    return jdbc.sql(
                "UPDATE carteira_observada SET verificada_em = :at"
                    + " WHERE usuario_id = :userId AND id = :walletId")
            .param("at", utc(at))
            .param("userId", userId)
            .param("walletId", walletId)
            .update()
        > 0;
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
