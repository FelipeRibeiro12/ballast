package com.felipe.ballast.projection;

import com.felipe.ballast.entries.CardBilling;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017), sempre por usuario_id.
@Repository
class SpendableQueries {

  record Benefit(UUID id, String name) {}

  // balance é o saldo realizado hoje do cofrinho com percentual.
  record SavingsPot(BigDecimal balance, BigDecimal cdiPercent) {}

  private final JdbcClient jdbc;

  SpendableQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<Integer> limitDay(UUID userId) {
    return jdbc.sql("SELECT dia_limite FROM preferencia_posso_gastar WHERE usuario_id = :userId")
        .param("userId", userId)
        .query(Integer.class)
        .optional();
  }

  void saveLimitDay(UUID userId, int day) {
    jdbc.sql(
            """
            INSERT INTO preferencia_posso_gastar (usuario_id, dia_limite) VALUES (:userId, :day)
            ON CONFLICT (usuario_id) DO UPDATE SET dia_limite = EXCLUDED.dia_limite
            """)
        .param("userId", userId)
        .param("day", day)
        .update();
  }

  void clearLimitDay(UUID userId) {
    jdbc.sql("DELETE FROM preferencia_posso_gastar WHERE usuario_id = :userId")
        .param("userId", userId)
        .update();
  }

  // A fatura âncora é a de vencimento mais tarde no mês (ADR-063). Desempata pelo fechamento e
  // pelo id para a escolha ser estável.
  Optional<CardBilling> anchorCard(UUID userId) {
    return jdbc.sql(
            """
            SELECT ca.dia_fechamento, ca.dia_vencimento
            FROM cartao ca
            JOIN conta c ON c.id = ca.conta_id AND c.usuario_id = ca.usuario_id AND NOT c.arquivada
            WHERE ca.usuario_id = :userId
            ORDER BY ca.dia_vencimento DESC, ca.dia_fechamento DESC, ca.conta_id
            LIMIT 1
            """)
        .param("userId", userId)
        .query(
            (rs, row) -> new CardBilling(rs.getInt("dia_fechamento"), rs.getInt("dia_vencimento")))
        .optional();
  }

  List<SavingsPot> savingsPots(UUID userId) {
    return jdbc.sql(
            """
            SELECT c.percentual_cdi,
              c.saldo_inicial + COALESCE(SUM(CASE l.tipo
                WHEN 'receita' THEN l.valor
                WHEN 'despesa' THEN -l.valor
                WHEN 'transferencia' THEN CASE WHEN te.id IS NOT NULL THEN l.valor ELSE -l.valor END
              END), 0) AS saldo
            FROM conta c
            LEFT JOIN lancamento l ON l.conta_id = c.id AND l.usuario_id = c.usuario_id
              AND l.status = 'realizado'
            LEFT JOIN transferencia te ON te.entrada_id = l.id AND te.usuario_id = l.usuario_id
            WHERE c.usuario_id = :userId AND c.tipo = 'cofrinho' AND NOT c.arquivada
              AND c.percentual_cdi IS NOT NULL
            GROUP BY c.id
            """)
        .param("userId", userId)
        .query(
            (rs, row) ->
                new SavingsPot(rs.getBigDecimal("saldo"), rs.getBigDecimal("percentual_cdi")))
        .list();
  }

  List<Benefit> activeBenefits(UUID userId) {
    return jdbc.sql(
            """
            SELECT id, nome FROM conta
            WHERE usuario_id = :userId AND NOT arquivada AND tipo = 'beneficio'
            ORDER BY lower(nome), id
            """)
        .param("userId", userId)
        .query((rs, row) -> new Benefit(rs.getObject("id", UUID.class), rs.getString("nome")))
        .list();
  }
}
