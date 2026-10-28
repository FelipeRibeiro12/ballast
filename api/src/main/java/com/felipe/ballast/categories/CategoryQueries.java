package com.felipe.ballast.categories;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017). Toda consulta filtra por usuario_id e id juntos.
@Repository
class CategoryQueries {

  record Row(UUID id, String name, String type, String seed, boolean hasChildren) {
    boolean isProtected() {
      return "outros".equals(seed) || "outras_receitas".equals(seed);
    }
  }

  private final JdbcClient jdbc;

  CategoryQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<Row> find(UUID userId, UUID id) {
    return jdbc.sql(
            """
            SELECT c.id, c.nome, c.tipo, c.semente,
              EXISTS (SELECT 1 FROM categoria f WHERE f.usuario_id = c.usuario_id
                AND f.pai_id = c.id) AS tem_filha
            FROM categoria c WHERE c.usuario_id = :userId AND c.id = :id
            FOR UPDATE
            """)
        .param("userId", userId)
        .param("id", id)
        .query(
            (rs, row) ->
                new Row(
                    rs.getObject("id", UUID.class),
                    rs.getString("nome"),
                    rs.getString("tipo"),
                    rs.getString("semente"),
                    rs.getBoolean("tem_filha")))
        .optional();
  }

  UUID insert(UUID userId, String name, String type) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :userId, :name, :type)")
        .param("id", id)
        .param("userId", userId)
        .param("name", name)
        .param("type", type)
        .update();
    return id;
  }

  int rename(UUID userId, UUID id, String name) {
    return jdbc.sql("UPDATE categoria SET nome = :name WHERE usuario_id = :userId AND id = :id")
        .param("name", name)
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  boolean isListedInBenefitAccount(UUID userId, UUID id) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM conta_categoria_permitida"
                + " WHERE usuario_id = :userId AND categoria_id = :id)")
        .param("userId", userId)
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  UUID findFallback(UUID userId, String type) {
    return jdbc.sql("SELECT id FROM categoria WHERE usuario_id = :userId AND semente = :seed")
        .param("userId", userId)
        .param("seed", "despesa".equals(type) ? "outros" : "outras_receitas")
        .query(UUID.class)
        .single();
  }

  // O teto da apagada soma ao da Outros no mesmo mês de vigência (ADR-064).
  void moveBudgets(UUID userId, UUID fromId, UUID toId) {
    jdbc.sql(
            """
            INSERT INTO orcamento (usuario_id, categoria_id, desde, teto)
            SELECT usuario_id, :toId, desde, teto FROM orcamento
            WHERE usuario_id = :userId AND categoria_id = :fromId AND teto IS NOT NULL
            ON CONFLICT (usuario_id, categoria_id, desde)
            DO UPDATE SET teto = COALESCE(orcamento.teto, 0) + EXCLUDED.teto
            """)
        .param("userId", userId)
        .param("fromId", fromId)
        .param("toId", toId)
        .update();
  }

  void moveEntries(UUID userId, UUID fromId, UUID toId) {
    jdbc.sql(
            "UPDATE lancamento SET categoria_id = :toId"
                + " WHERE usuario_id = :userId AND categoria_id = :fromId")
        .param("userId", userId)
        .param("fromId", fromId)
        .param("toId", toId)
        .update();
    jdbc.sql(
            "UPDATE template_lancamento SET categoria_id = :toId"
                + " WHERE usuario_id = :userId AND categoria_id = :fromId")
        .param("userId", userId)
        .param("fromId", fromId)
        .param("toId", toId)
        .update();
  }

  void delete(UUID userId, UUID id) {
    jdbc.sql("DELETE FROM categoria WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .update();
  }
}
