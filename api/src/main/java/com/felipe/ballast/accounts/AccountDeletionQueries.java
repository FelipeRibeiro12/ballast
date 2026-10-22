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

  // A conta e as do outro lado das transferências, em ordem de id, como a transferência trava
  // (EntryService.lockOrder). Sem lista vinda do Java: o driver aceita no máximo 32767
  // parâmetros. Devolve os ids travados.
  List<UUID> lockAccountAndCounterparts(UUID userId, UUID accountId) {
    return jdbc.sql(
            """
            SELECT id FROM conta
            WHERE usuario_id = :userId
              AND (id = :id OR id IN (
                SELECT CASE WHEN conta_saida_id = :id THEN conta_entrada_id ELSE conta_saida_id END
                FROM transferencia
                WHERE usuario_id = :userId AND (conta_saida_id = :id OR conta_entrada_id = :id)))
            ORDER BY id
            FOR UPDATE
            """)
        .param("userId", userId)
        .param("id", accountId)
        .query(UUID.class)
        .list();
  }

  // Templates antes das contas: é a ordem do job e do reativar (template, depois a conta no
  // INSERT da ocorrência), então apagar a conta não fecha ciclo com eles.
  void lockTemplates(UUID userId, UUID accountId) {
    jdbc.sql(
            "SELECT id FROM template_lancamento WHERE usuario_id = :userId AND conta_id = :id"
                + " ORDER BY id FOR UPDATE")
        .param("userId", userId)
        .param("id", accountId)
        .query(UUID.class)
        .list();
  }

  // Lançamentos da conta antes das contas: editar e confirmar lançamento travam o lançamento e
  // depois a conta, e apagar a conta na ordem inversa fecharia ciclo com eles.
  void lockEntries(UUID userId, UUID accountId) {
    jdbc.sql(
            "SELECT count(*) FROM (SELECT id FROM lancamento"
                + " WHERE usuario_id = :userId AND conta_id = :id ORDER BY id FOR UPDATE) s")
        .param("userId", userId)
        .param("id", accountId)
        .query(Long.class)
        .single();
  }

  // A fatura só conta como pagamento a perna de transferência que entra no cartão. Convertida em
  // receita, ela sairia da fatura paga e cairia como estorno em outra (revisão do PR #21).
  boolean paidACardInvoice(UUID userId, UUID accountId) {
    return jdbc.sql(
            """
            SELECT EXISTS (
              SELECT 1 FROM transferencia t
              JOIN conta c ON c.id = t.conta_entrada_id AND c.usuario_id = t.usuario_id
              WHERE t.usuario_id = :userId AND t.conta_saida_id = :id
                AND c.tipo = 'cartao_credito')
            """)
        .param("userId", userId)
        .param("id", accountId)
        .query(Boolean.class)
        .single();
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

  // Desfaz as transferências com outra conta e transforma a perna de lá em lançamento comum, num
  // comando só: a FK da perna (que inclui o tipo) é conferida no fim dele, quando a transferência
  // já saiu. Saída da outra conta vira despesa em "Outros", entrada vira receita em "Outras
  // receitas" (ADR-036). A categoria vem da semente que todo usuário tem; ainda não há CRUD de
  // categoria que a apague, e o CHECK da V7 exige categoria fora de transferência.
  void turnForeignLegsIntoPlainEntries(UUID userId, UUID accountId) {
    jdbc.sql(
            """
            WITH desfeita AS (
              DELETE FROM transferencia
              WHERE usuario_id = :userId AND (conta_saida_id = :id OR conta_entrada_id = :id)
              RETURNING CASE WHEN conta_saida_id = :id THEN entrada_id ELSE saida_id END AS perna,
                        CASE WHEN conta_saida_id = :id THEN 'receita' ELSE 'despesa' END AS vira
            )
            UPDATE lancamento l
            SET tipo = d.vira,
                categoria_id = (SELECT c.id FROM categoria c
                                WHERE c.usuario_id = :userId AND c.tipo = d.vira
                                  AND c.nome = CASE d.vira WHEN 'receita' THEN 'Outras receitas'
                                                           ELSE 'Outros' END)
            FROM desfeita d
            WHERE l.usuario_id = :userId AND l.id = d.perna
            """)
        .param("userId", userId)
        .param("id", accountId)
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

  // Divisão, template e regra, nessa ordem por causa das FKs. Template e regra saem no mesmo
  // comando, para não levar a lista de regras pelo Java.
  void deleteTemplates(UUID userId, UUID accountId) {
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
    jdbc.sql(
            """
            WITH apagado AS (
              DELETE FROM template_lancamento WHERE usuario_id = :userId AND conta_id = :id
              RETURNING regra_id
            )
            DELETE FROM regra_recorrencia
            WHERE usuario_id = :userId AND id IN (SELECT regra_id FROM apagado)
            """)
        .param("userId", userId)
        .param("id", accountId)
        .update();
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
