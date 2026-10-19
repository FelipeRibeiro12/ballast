package com.felipe.ballast.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// A busca é sempre pelo hash do token: o banco nunca recebe o token em claro.
@Repository
class SessionQueries {

  private static final Logger log = LoggerFactory.getLogger(SessionQueries.class);
  private static final String FIND_ACTIVE_SQL =
      """
      SELECT usuario_id FROM sessao
      WHERE token_hash = ? AND revogada_em IS NULL AND expira_em > ?
      """;
  private static final int NETWORK_TIMEOUT_MILLIS = 2000;

  private final JdbcClient jdbc;
  private final JdbcTemplate template;

  SessionQueries(JdbcClient jdbc, DataSource dataSource) {
    this.jdbc = jdbc;
    this.template = new JdbcTemplate(dataSource);
  }

  // Toda rota autenticada começa por esta consulta. O Hikari não valida a conexão usada há menos
  // de 500 ms e, com o banco congelado, ela ficava presa até o socketTimeout de 30 s. Aqui o teto
  // é de 2 s, só enquanto a conexão está emprestada, e volta ao valor anterior. Sem
  // setQueryTimeout pelo mesmo motivo do HealthRepository: o cancelamento abre outra conexão, que
  // também trava.
  Optional<UUID> findActiveUserId(byte[] tokenHash, Instant now) {
    return template.execute(
        (Connection connection) -> {
          int previousTimeout = connection.getNetworkTimeout();
          connection.setNetworkTimeout(Runnable::run, NETWORK_TIMEOUT_MILLIS);
          try (PreparedStatement statement = connection.prepareStatement(FIND_ACTIVE_SQL)) {
            statement.setBytes(1, tokenHash);
            statement.setObject(2, utc(now));
            try (ResultSet rows = statement.executeQuery()) {
              return rows.next()
                  ? Optional.of(rows.getObject(1, UUID.class))
                  : Optional.<UUID>empty();
            }
          } finally {
            restoreNetworkTimeout(connection, previousTimeout);
          }
        });
  }

  // Estourado o teto, o Hikari já descartou a conexão e restaurar lança outra exceção por cima do
  // erro de rede, que viraria 500 em vez de 503. Descartada, não há valor a devolver ao pool.
  private static void restoreNetworkTimeout(Connection connection, int timeoutMillis) {
    try {
      connection.setNetworkTimeout(Runnable::run, timeoutMillis);
    } catch (SQLException e) {
      log.debug("sessão: conexão descartada antes de restaurar o teto de rede");
    }
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
