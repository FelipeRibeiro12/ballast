package com.felipe.ballast.identity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// A busca é sempre pelo hash do token: o banco nunca recebe o token em claro.
@Repository
class SessionQueries {

  private final JdbcClient jdbc;

  SessionQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<UUID> findActiveUserId(byte[] tokenHash, Instant now) {
    return jdbc.sql(
            """
            SELECT usuario_id FROM sessao
            WHERE token_hash = :hash AND revogada_em IS NULL AND expira_em > :now
            """)
        .param("hash", tokenHash)
        .param("now", utc(now))
        .query(UUID.class)
        .optional();
  }

  // O filtro revogada_em IS NULL preserva o instante do primeiro logout. Vazio quando não havia
  // sessão ativa com esse hash; senão devolve o dono, para o log de segurança.
  Optional<UUID> revoke(byte[] tokenHash, Instant now) {
    return jdbc.sql(
            """
            UPDATE sessao SET revogada_em = :now
            WHERE token_hash = :hash AND revogada_em IS NULL
            RETURNING usuario_id
            """)
        .param("hash", tokenHash)
        .param("now", utc(now))
        .query(UUID.class)
        .optional();
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
