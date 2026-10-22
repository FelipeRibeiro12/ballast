package com.felipe.ballast.accounts;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Apagar uma conta mexe em quase todas as tabelas (ADR-036). Fica num lugar só para a ordem dos
// DELETEs, que as FKs exigem, ficar visível. Toda instrução filtra por usuario_id.
@Repository
class AccountDeletionQueries {

  record Summary(long entries, long transfers, long recurrences, long cryptoMovements) {}

  // otherLeg é a perna da outra conta; becomes é o tipo que ela vira quando a ligação some.
  record ForeignLeg(UUID transferId, UUID otherLeg, String becomes) {}

  private final JdbcClient jdbc;

  AccountDeletionQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  boolean exists(UUID userId, UUID accountId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM conta WHERE usuario_id = :userId AND id = :id)")
        .param("userId", userId)
        .param("id", accountId)
        .query(Boolean.class)
        .single();
  }

  Summary summary(UUID userId, UUID accountId) {
    return jdbc.sql(
            """
            SELECT
              (SELECT count(*) FROM lancamento l
               WHERE l.usuario_id = :userId AND l.conta_id = :id AND l.tipo <> 'transferencia'),
              (SELECT count(*) FROM transferencia
               WHERE usuario_id = :userId AND (conta_saida_id = :id OR conta_entrada_id = :id)),
              (SELECT count(*) FROM template_lancamento WHERE usuario_id = :userId AND conta_id = :id),
              (SELECT count(*) FROM movimento_cripto WHERE usuario_id = :userId AND conta_id = :id)
            """)
        .param("userId", userId)
        .param("id", accountId)
        .query((rs, row) -> new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)))
        .single();
  }

  // Contas do outro lado das transferências, para travar todas em ordem de id junto com a conta.
  List<UUID> counterparts(UUID userId, UUID accountId) {
    return jdbc.sql(
            """
            SELECT DISTINCT CASE WHEN conta_saida_id = :id THEN conta_entrada_id
                                 ELSE conta_saida_id END
            FROM transferencia
            WHERE usuario_id = :userId AND (conta_saida_id = :id OR conta_entrada_id = :id)
            """)
        .param("userId", userId)
        .param("id", accountId)
        .query(UUID.class)
        .list();
  }

  // Mesma ordem da transferência (EntryService.lockOrder): duas escritas nunca se cruzam.
  List<UUID> lockInOrder(UUID userId, List<UUID> accountIds) {
    return jdbc.sql(
            "SELECT id FROM conta WHERE usuario_id = :userId AND id IN (:ids) ORDER BY id"
                + " FOR UPDATE")
        .param("userId", userId)
        .param("ids", accountIds)
        .query(UUID.class)
        .list();
  }

  // O cartão aponta para a conta de pagamento por FK; apagar a conta deixaria o cartão sem ela.
  boolean paysACard(UUID userId, UUID accountId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM cartao WHERE usuario_id = :userId"
                + " AND conta_pagamento_id = :id AND conta_id <> :id)")
        .param("userId", userId)
        .param("id", accountId)
        .query(Boolean.class)
        .single();
  }

  // Saída da outra conta vira despesa; entrada vira receita (ADR-036).
  List<ForeignLeg> foreignLegs(UUID userId, UUID accountId) {
    return jdbc.sql(
            """
            SELECT id,
                   CASE WHEN conta_saida_id = :id THEN entrada_id ELSE saida_id END AS outra,
                   CASE WHEN conta_saida_id = :id THEN 'receita' ELSE 'despesa' END AS vira
            FROM transferencia
            WHERE usuario_id = :userId AND (conta_saida_id = :id OR conta_entrada_id = :id)
            """)
        .param("userId", userId)
        .param("id", accountId)
        .query(
            (rs, row) ->
                new ForeignLeg(
                    rs.getObject("id", UUID.class),
                    rs.getObject("outra", UUID.class),
                    rs.getString("vira")))
        .list();
  }

  void deleteTransfers(UUID userId, List<UUID> transferIds) {
    jdbc.sql("DELETE FROM transferencia WHERE usuario_id = :userId AND id IN (:ids)")
        .param("userId", userId)
        .param("ids", transferIds)
        .update();
  }

  // A categoria vem da semente que todo usuário tem ("Outros" e "Outras receitas"); ainda não há
  // CRUD de categoria que a apague. O CHECK da V7 exige categoria fora de transferência.
  void turnIntoPlainEntries(UUID userId, List<UUID> legIds, String type) {
    jdbc.sql(
            """
            UPDATE lancamento SET tipo = :type,
                   categoria_id = (SELECT id FROM categoria
                                   WHERE usuario_id = :userId AND tipo = :type
                                     AND nome = CASE :type WHEN 'receita' THEN 'Outras receitas'
                                                           ELSE 'Outros' END)
            WHERE usuario_id = :userId AND id IN (:ids)
            """)
        .param("userId", userId)
        .param("type", type)
        .param("ids", legIds)
        .update();
  }

  void deleteEntries(UUID userId, UUID accountId) {
    jdbc.sql("DELETE FROM lancamento WHERE usuario_id = :userId AND conta_id = :id")
        .param("userId", userId)
        .param("id", accountId)
        .update();
  }

  // Ocorrência movida para outra conta pelo PUT continua apontando para o template desta. Ela
  // fica como lançamento avulso; parcela_ordem só existe com template (V9).
  void detachOccurrencesOfAccountTemplates(UUID userId, UUID accountId) {
    jdbc.sql(
            """
            UPDATE lancamento SET template_id = NULL, ocorrencia_data = NULL,
                                  parcela_ordem = NULL, desgarrado = false
            WHERE usuario_id = :userId
              AND template_id IN (SELECT id FROM template_lancamento
                                  WHERE usuario_id = :userId AND conta_id = :id)
            """)
        .param("userId", userId)
        .param("id", accountId)
        .update();
  }

  // Divisão, template e regra, nessa ordem por causa das FKs.
  void deleteTemplates(UUID userId, UUID accountId) {
    List<UUID> rules =
        jdbc.sql(
                "SELECT regra_id FROM template_lancamento"
                    + " WHERE usuario_id = :userId AND conta_id = :id")
            .param("userId", userId)
            .param("id", accountId)
            .query(UUID.class)
            .list();
    jdbc.sql(
            """
            DELETE FROM template_divisao
            WHERE usuario_id = :userId
              AND template_id IN (SELECT id FROM template_lancamento
                                  WHERE usuario_id = :userId AND conta_id = :id)
            """)
        .param("userId", userId)
        .param("id", accountId)
        .update();
    jdbc.sql("DELETE FROM template_lancamento WHERE usuario_id = :userId AND conta_id = :id")
        .param("userId", userId)
        .param("id", accountId)
        .update();
    if (!rules.isEmpty()) {
      jdbc.sql("DELETE FROM regra_recorrencia WHERE usuario_id = :userId AND id IN (:ids)")
          .param("userId", userId)
          .param("ids", rules)
          .update();
    }
  }

  // A lista de categorias permitidas sai junto pelo ON DELETE CASCADE da V12.
  int deleteAccountRows(UUID userId, UUID accountId) {
    jdbc.sql("DELETE FROM movimento_cripto WHERE usuario_id = :userId AND conta_id = :id")
        .param("userId", userId)
        .param("id", accountId)
        .update();
    jdbc.sql("DELETE FROM cartao WHERE usuario_id = :userId AND conta_id = :id")
        .param("userId", userId)
        .param("id", accountId)
        .update();
    return jdbc.sql("DELETE FROM conta WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", accountId)
        .update();
  }
}
