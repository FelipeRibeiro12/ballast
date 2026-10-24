package com.felipe.ballast.web;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

// Linhas da tabela idempotencia (ADR-056). Cada método é um comando só, em autocommit: a linha
// precisa ficar visível para as outras requisições assim que nasce, antes de a escrita começar.
// Toda consulta filtra por usuario_id.
class IdempotencyStore {

  // Em andamento há mais que isto é requisição que morreu no meio: o dono do pedido seguinte
  // assume. Folga sobre o socketTimeout de 30 s, o maior tempo que uma escrita viva leva.
  private static final String STALE = "60 seconds";

  record Stored(
      String route,
      String bodyHash,
      Integer status,
      String responseBody,
      OffsetDateTime createdAt) {

    boolean isDone() {
      return status != null;
    }
  }

  private final JdbcClient jdbc;

  IdempotencyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Devolve a marca de criação, que identifica o dono da linha: só quem a tem conclui ou desiste.
  // Vazio quando a chave já existe (em andamento ou concluída).
  Optional<OffsetDateTime> claim(UUID userId, String key, String route, String bodyHash) {
    return jdbc.sql(
            "INSERT INTO idempotencia (usuario_id, chave, rota, corpo_hash)"
                + " VALUES (:userId, :key, :route, :bodyHash)"
                + " ON CONFLICT (usuario_id, chave) DO NOTHING RETURNING criada_em")
        .param("userId", userId)
        .param("key", key)
        .param("route", route)
        .param("bodyHash", bodyHash)
        .query(OffsetDateTime.class)
        .optional();
  }

  Optional<Stored> find(UUID userId, String key) {
    return jdbc.sql(
            "SELECT rota, corpo_hash, status, corpo_resposta, criada_em FROM idempotencia"
                + " WHERE usuario_id = :userId AND chave = :key")
        .param("userId", userId)
        .param("key", key)
        .query(
            (rs, row) ->
                new Stored(
                    rs.getString("rota"),
                    rs.getString("corpo_hash"),
                    rs.getObject("status", Integer.class),
                    rs.getString("corpo_resposta"),
                    rs.getObject("criada_em", OffsetDateTime.class)))
        .optional();
  }

  // Assume uma linha em andamento que passou do prazo. Compara a marca vista, então de duas
  // requisições que tentam ao mesmo tempo só uma leva. Devolve a marca nova.
  Optional<OffsetDateTime> takeOver(UUID userId, String key, OffsetDateTime seenCreatedAt) {
    return jdbc.sql(
            "UPDATE idempotencia SET criada_em = clock_timestamp()"
                + " WHERE usuario_id = :userId AND chave = :key AND status IS NULL"
                + " AND criada_em = :seen AND criada_em < now() - interval '"
                + STALE
                + "' RETURNING criada_em")
        .param("userId", userId)
        .param("key", key)
        .param("seen", seenCreatedAt)
        .query(OffsetDateTime.class)
        .optional();
  }

  boolean finish(UUID userId, String key, OffsetDateTime owner, int status, String responseBody) {
    return jdbc.sql(
                "UPDATE idempotencia SET status = :status, corpo_resposta = :body"
                    + " WHERE usuario_id = :userId AND chave = :key AND status IS NULL"
                    + " AND criada_em = :owner")
            .param("status", status)
            .param("body", responseBody)
            .param("userId", userId)
            .param("key", key)
            .param("owner", owner)
            .update()
        == 1;
  }

  // Resposta que não foi 2xx não fica guardada: a chave volta a valer para o cliente corrigir e
  // tentar de novo.
  void abandon(UUID userId, String key, OffsetDateTime owner) {
    jdbc.sql(
            "DELETE FROM idempotencia WHERE usuario_id = :userId AND chave = :key"
                + " AND status IS NULL AND criada_em = :owner")
        .param("userId", userId)
        .param("key", key)
        .param("owner", owner)
        .update();
  }
}
