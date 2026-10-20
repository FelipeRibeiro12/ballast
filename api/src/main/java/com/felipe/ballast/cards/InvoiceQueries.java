package com.felipe.ballast.cards;

import com.felipe.ballast.entries.CardBilling;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Leitura da fatura em SQL (ADR-017), sempre filtrada por usuario_id e conta_id. O ciclo vem de
// cartao; os valores são agregados por data_caixa e a aplicação distribui nas faturas.
@Repository
class InvoiceQueries {

  private final JdbcClient jdbc;

  InvoiceQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Vazio quando a conta não existe, é de outro usuário ou não é cartão: os três viram 404.
  Optional<CardBilling> findBilling(UUID userId, UUID accountId) {
    return jdbc.sql(
            "SELECT dia_fechamento, dia_vencimento FROM cartao"
                + " WHERE usuario_id = :userId AND conta_id = :accountId")
        .param("userId", userId)
        .param("accountId", accountId)
        .query(
            (rs, row) -> new CardBilling(rs.getInt("dia_fechamento"), rs.getInt("dia_vencimento")))
        .optional();
  }

  // Despesa menos receita por data_caixa, previstas e realizadas. A perna de transferência fica
  // fora pelo filtro de tipo: pagamento não é compra.
  Map<LocalDate, BigDecimal> totalsByCashDate(
      UUID userId, UUID accountId, LocalDate from, LocalDate to) {
    return byDate(
        """
        SELECT l.data_caixa AS dia,
               SUM(CASE l.tipo WHEN 'despesa' THEN l.valor ELSE -l.valor END) AS valor
        FROM lancamento l
        WHERE l.usuario_id = :userId AND l.conta_id = :accountId
          AND l.data_caixa BETWEEN :from AND :to
          AND l.status IN ('previsto', 'realizado') AND l.tipo IN ('receita', 'despesa')
        GROUP BY l.data_caixa
        """,
        userId,
        accountId,
        from,
        to);
  }

  // Pernas de entrada de transferência na conta, só realizadas. A direção vem da ligação (ADR-025):
  // quem aparece em transferencia.entrada_id é a perna que soma na conta.
  Map<LocalDate, BigDecimal> paymentsByCashDate(
      UUID userId, UUID accountId, LocalDate from, LocalDate to) {
    return byDate(
        """
        SELECT l.data_caixa AS dia, SUM(l.valor) AS valor
        FROM lancamento l
        JOIN transferencia te ON te.entrada_id = l.id AND te.usuario_id = l.usuario_id
        WHERE l.usuario_id = :userId AND l.conta_id = :accountId
          AND l.data_caixa BETWEEN :from AND :to AND l.status = 'realizado'
        GROUP BY l.data_caixa
        """,
        userId,
        accountId,
        from,
        to);
  }

  private Map<LocalDate, BigDecimal> byDate(
      String sql, UUID userId, UUID accountId, LocalDate from, LocalDate to) {
    Map<LocalDate, BigDecimal> result = new LinkedHashMap<>();
    jdbc.sql(sql)
        .param("userId", userId)
        .param("accountId", accountId)
        .param("from", from)
        .param("to", to)
        .query(
            rs -> {
              result.put(rs.getObject("dia", LocalDate.class), rs.getBigDecimal("valor"));
            });
    return result;
  }
}
