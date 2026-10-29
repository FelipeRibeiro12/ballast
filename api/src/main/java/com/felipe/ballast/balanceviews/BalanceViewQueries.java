package com.felipe.ballast.balanceviews;

import com.felipe.ballast.accounts.AccountType;
import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017). Toda consulta filtra por usuario_id.
@Repository
class BalanceViewQueries {

  record View(UUID id, String title, List<AccountType> types) {}

  private final JdbcClient jdbc;

  BalanceViewQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  List<View> list(UUID userId) {
    return jdbc.sql(
            """
            SELECT id, titulo, tipos FROM visao_saldo
            WHERE usuario_id = :userId ORDER BY criado_em, id
            """)
        .param("userId", userId)
        .query(
            (rs, row) -> {
              Array types = rs.getArray("tipos");
              return new View(
                  rs.getObject("id", UUID.class),
                  rs.getString("titulo"),
                  Arrays.stream((String[]) types.getArray()).map(AccountType::fromValue).toList());
            })
        .list();
  }

  long count(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM visao_saldo WHERE usuario_id = :userId")
        .param("userId", userId)
        .query(Long.class)
        .single();
  }

  // Trava as linhas do usuário até o commit, para dois DELETE não apagarem a última juntos.
  long countLocked(UUID userId) {
    return jdbc.sql("SELECT id FROM visao_saldo WHERE usuario_id = :userId FOR UPDATE")
        .param("userId", userId)
        .query(UUID.class)
        .list()
        .size();
  }

  void insert(UUID userId, String title, List<AccountType> types) {
    jdbc.sql(
            """
            INSERT INTO visao_saldo (id, usuario_id, titulo, tipos)
            VALUES (:id, :userId, :title, CAST(:types AS text[]))
            """)
        .param("id", UUID.randomUUID())
        .param("userId", userId)
        .param("title", title)
        .param("types", values(types))
        .update();
  }

  int update(UUID userId, UUID id, String title, List<AccountType> types) {
    return jdbc.sql(
            """
            UPDATE visao_saldo SET titulo = :title, tipos = CAST(:types AS text[])
            WHERE usuario_id = :userId AND id = :id
            """)
        .param("title", title)
        .param("types", values(types))
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  int delete(UUID userId, UUID id) {
    return jdbc.sql("DELETE FROM visao_saldo WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  // Literal de array do Postgres: os valores vêm do enum, nunca de texto do cliente.
  private static String values(List<AccountType> types) {
    return "{" + String.join(",", types.stream().map(AccountType::value).toList()) + "}";
  }
}
