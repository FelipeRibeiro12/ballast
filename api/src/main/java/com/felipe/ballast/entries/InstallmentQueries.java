package com.felipe.ballast.entries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL da compra parcelada (ADR-017). Toda consulta filtra por usuario_id. A compra é identificada
// pelo id da primeira parcela, que é o parcela_de_id de todas.
@Repository
class InstallmentQueries {

  private final JdbcClient jdbc;

  InstallmentQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // parcela_tipo_conta é gerada pelo banco e nunca entra no INSERT. A primeira parcela é inserida
  // antes das outras, porque as demais apontam para ela pela FK composta.
  void insertInstallment(
      UUID userId,
      UUID id,
      UUID firstId,
      InstallmentInput input,
      InstallmentPlan.Part part,
      int total) {
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, descricao, categoria_id, conta_id,
                                    valor, data_competencia, data_caixa, status,
                                    parcela_de_id, parcela_num, parcela_total)
            VALUES (:id, :userId, 'despesa', :title, :description, :categoryId, :accountId,
                    :amount, :competence, :cash, 'realizado', :firstId, :number, :total)
            """)
        .param("id", id)
        .param("userId", userId)
        .param("title", input.title())
        .param("description", input.description())
        .param("categoryId", input.categoryId())
        .param("accountId", input.accountId())
        .param("amount", part.amount())
        .param("competence", input.date())
        .param("cash", part.cashDate())
        .param("firstId", firstId)
        .param("number", part.number())
        .param("total", total)
        .update();
  }

  // Trava as parcelas da compra, na ordem da parcela, até o fim da transação. Lista vazia quando o
  // id não é de uma primeira parcela do usuário (outra parcela, de outro usuário ou inexistente).
  List<UUID> lockInstallments(UUID userId, UUID purchaseId) {
    return jdbc.sql(
            "SELECT id FROM lancamento WHERE usuario_id = :userId AND parcela_de_id = :purchaseId"
                + " ORDER BY parcela_num FOR UPDATE")
        .param("userId", userId)
        .param("purchaseId", purchaseId)
        .query(UUID.class)
        .list();
  }

  Optional<InstallmentPurchase> find(UUID userId, UUID purchaseId) {
    record Row(
        UUID id,
        String title,
        String description,
        BigDecimal amount,
        UUID categoryId,
        UUID accountId,
        LocalDate competence,
        LocalDate cash,
        int number,
        int total,
        OffsetDateTime createdAt) {}
    List<Row> rows =
        jdbc.sql(
                """
                SELECT id, titulo, descricao, valor, categoria_id, conta_id, data_competencia,
                       data_caixa, parcela_num, parcela_total, criado_em
                FROM lancamento
                WHERE usuario_id = :userId AND parcela_de_id = :purchaseId
                ORDER BY parcela_num
                """)
            .param("userId", userId)
            .param("purchaseId", purchaseId)
            .query(
                (rs, row) ->
                    new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("titulo"),
                        rs.getString("descricao"),
                        rs.getBigDecimal("valor"),
                        rs.getObject("categoria_id", UUID.class),
                        rs.getObject("conta_id", UUID.class),
                        rs.getObject("data_competencia", LocalDate.class),
                        rs.getObject("data_caixa", LocalDate.class),
                        rs.getInt("parcela_num"),
                        rs.getInt("parcela_total"),
                        rs.getObject("criado_em", OffsetDateTime.class)))
            .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Row first = rows.getFirst();
    BigDecimal total = BigDecimal.ZERO;
    List<InstallmentPurchase.Item> items = new ArrayList<>(rows.size());
    for (Row row : rows) {
      total = total.add(row.amount());
      items.add(new InstallmentPurchase.Item(row.id(), row.number(), row.amount(), row.cash()));
    }
    return Optional.of(
        new InstallmentPurchase(
            purchaseId,
            first.title(),
            first.description(),
            total,
            first.total(),
            first.categoryId(),
            first.accountId(),
            first.competence(),
            first.createdAt().toInstant(),
            items));
  }

  // Campo nulo mantém o que está gravado; descrição tem o sinal à parte porque nula também é um
  // valor (limpar). O CAST dá tipo ao parâmetro nulo.
  int updateTexts(
      UUID userId,
      UUID purchaseId,
      String title,
      boolean descriptionGiven,
      String description,
      UUID categoryId) {
    return jdbc.sql(
            """
            UPDATE lancamento
            SET titulo = COALESCE(CAST(:title AS text), titulo),
                descricao = CASE WHEN :descriptionGiven THEN CAST(:description AS text)
                                 ELSE descricao END,
                categoria_id = COALESCE(CAST(:categoryId AS uuid), categoria_id)
            WHERE usuario_id = :userId AND parcela_de_id = :purchaseId
            """)
        .param("title", title)
        .param("descriptionGiven", descriptionGiven)
        .param("description", description)
        .param("categoryId", categoryId)
        .param("userId", userId)
        .param("purchaseId", purchaseId)
        .update();
  }

  // Uma instrução só: a FK da primeira parcela checa no fim do comando, com todas já apagadas.
  int deleteAll(UUID userId, UUID purchaseId) {
    return jdbc.sql(
            "DELETE FROM lancamento WHERE parcela_de_id = :purchaseId AND usuario_id = :userId")
        .param("purchaseId", purchaseId)
        .param("userId", userId)
        .update();
  }
}
