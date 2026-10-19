package com.felipe.ballast.health;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class HealthRepository {

  private static final String SQL = "SELECT mensagem FROM health_check WHERE id = 1";
  private static final int NETWORK_TIMEOUT_MILLIS = 2000;

  private final JdbcTemplate jdbc;

  HealthRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  // Erros do JDBC saem como DataAccessException, traduzidos pelo JdbcTemplate.
  String findMessage() {
    return jdbc.execute((Connection connection) -> query(connection));
  }

  // Sem setQueryTimeout de propósito: o cancelamento abre outra conexão, que com o banco congelado
  // também trava (até 10 s), e o driver espera por ele antes de devolver o erro. O teto de socket
  // resolve o lock e o congelamento do mesmo jeito, ao custo de fechar a conexão. Vale só enquanto
  // ela está emprestada ao health e volta ao valor anterior, sem cortar as outras rotas.
  private String query(Connection connection) throws SQLException {
    int previousTimeout = connection.getNetworkTimeout();
    connection.setNetworkTimeout(Runnable::run, NETWORK_TIMEOUT_MILLIS);
    try (PreparedStatement statement = connection.prepareStatement(SQL)) {
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new EmptyResultDataAccessException(1);
        }
        return rows.getString(1);
      }
    } finally {
      connection.setNetworkTimeout(Runnable::run, previousTimeout);
    }
  }
}
