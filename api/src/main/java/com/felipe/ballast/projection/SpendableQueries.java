package com.felipe.ballast.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017), sempre por usuario_id.
@Repository
class SpendableQueries {

  record Salary(LocalDate date, BigDecimal amount) {}

  record Benefit(UUID id, String name) {}

  private final JdbcClient jdbc;

  SpendableQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Salário é receita na categoria Salário da semente, ou numa filha dela, numa conta ativa que
  // não é de benefício (ADR-048). O do dia já é dinheiro de hoje: o próximo é o de depois.
  // Salário dividido (ADR-027) chega em partes; a próxima parte já é o próximo salário.
  Optional<Salary> nextSalary(UUID userId, LocalDate today, LocalDate until) {
    return jdbc.sql(
            """
            SELECT l.data_caixa, SUM(l.valor) AS valor
            FROM lancamento l
            JOIN conta c ON c.usuario_id = :userId AND c.id = l.conta_id
              AND NOT c.arquivada AND c.tipo <> 'beneficio'
            JOIN categoria k ON k.usuario_id = :userId AND k.id = l.categoria_id
            LEFT JOIN categoria p ON p.usuario_id = :userId AND p.id = k.pai_id
            WHERE l.usuario_id = :userId AND l.tipo = 'receita'
              AND l.status IN ('previsto', 'realizado')
              AND l.data_caixa > :today AND l.data_caixa <= :until
              AND (k.nome = 'Salário' OR p.nome = 'Salário')
            GROUP BY l.data_caixa
            ORDER BY l.data_caixa
            LIMIT 1
            """)
        .param("userId", userId)
        .param("today", today)
        .param("until", until)
        .query(
            (rs, row) ->
                new Salary(rs.getObject("data_caixa", LocalDate.class), rs.getBigDecimal("valor")))
        .optional();
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
