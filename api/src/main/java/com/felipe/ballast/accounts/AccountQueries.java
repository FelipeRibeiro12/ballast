package com.felipe.ballast.accounts;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Leitura com saldo, em SQL (ADR-017). Toda consulta filtra por usuario_id.
// Saldo: saldo_inicial mais receitas menos despesas, mais entradas menos saídas de transferência
// (ADR-024, ADR-025), só realizados, calculado aqui para a listagem fazer uma ida ao banco só.
// Nunca é coluna. Sem ligação de entrada, a perna de transferência é saída.
@Repository
class AccountQueries {

  // MATERIALIZED força um agregado só por consulta. Sem ele o planejador pode refazer o agregado
  // para cada conta (nested loop) quando subestima as linhas.
  private static final String MOVEMENT_START =
      """
      WITH movement AS MATERIALIZED (
        SELECT l.conta_id,
               SUM(CASE l.tipo
                     WHEN 'receita' THEN l.valor
                     WHEN 'despesa' THEN -l.valor
                     WHEN 'transferencia' THEN
                       CASE WHEN te.id IS NOT NULL THEN l.valor ELSE -l.valor END
                   END) AS movimento
        FROM lancamento l
        LEFT JOIN transferencia te ON te.entrada_id = l.id AND te.usuario_id = l.usuario_id
        WHERE l.usuario_id = :userId AND l.status = 'realizado'
      """;

  private static final String MOVEMENT_END =
      """
        GROUP BY l.conta_id
      )
      SELECT c.id, c.nome, c.tipo, c.moeda, c.saldo_inicial,
             c.saldo_inicial + COALESCE(m.movimento, 0) AS saldo,
             c.arquivada, c.criada_em, c.percentual_cdi, c.em_investimentos,
             ca.limite, ca.dia_fechamento, ca.dia_vencimento, ca.conta_pagamento_id,
             CASE WHEN c.tipo = 'beneficio' THEN
               ARRAY(SELECT p.categoria_id FROM conta_categoria_permitida p
                     WHERE p.conta_id = c.id AND p.usuario_id = c.usuario_id
                     ORDER BY p.categoria_id)
             END AS categorias_permitidas
      FROM conta c
      LEFT JOIN movement m ON m.conta_id = c.id
      LEFT JOIN cartao ca ON ca.conta_id = c.id AND ca.usuario_id = c.usuario_id
      """;

  private static final String LIST_COLUMNS = MOVEMENT_START + MOVEMENT_END;
  // O detalhe filtra o agregado também pela conta pedida, para não somar o usuário inteiro.
  private static final String FIND_COLUMNS =
      MOVEMENT_START + "  AND l.conta_id = :id\n" + MOVEMENT_END;

  private final JdbcClient jdbc;

  AccountQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  List<AccountSummary> list(UUID userId, boolean archived) {
    return jdbc.sql(
            LIST_COLUMNS
                + """
                WHERE c.usuario_id = :userId AND c.arquivada = :archived
                ORDER BY lower(c.nome), c.id
                """)
        .param("userId", userId)
        .param("archived", archived)
        .query(AccountQueries::map)
        .list();
  }

  Optional<AccountSummary> find(UUID userId, UUID id) {
    return jdbc.sql(
            FIND_COLUMNS
                + """
                WHERE c.usuario_id = :userId AND c.id = :id
                """)
        .param("userId", userId)
        .param("id", id)
        .query(AccountQueries::map)
        .optional();
  }

  record PaymentAccount(boolean eligible) {}

  // Conta de pagamento do cartão, lida só pelo dono. Elegível é corrente ou poupança ativa
  // (aceita_pagamento_cartao é coluna gerada). A FK da V10 segura a corrida; isto dá a mensagem.
  Optional<PaymentAccount> findPaymentAccount(UUID userId, UUID id) {
    return jdbc.sql(
            "SELECT aceita_pagamento_cartao AND NOT arquivada FROM conta"
                + " WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .query((rs, row) -> new PaymentAccount(rs.getBoolean(1)))
        .optional();
  }

  // Trava a linha da conta até o fim da transação e devolve o tipo. Vazio para conta alheia ou
  // inexistente. É a trava que a V12 exige de quem troca a lista: o gatilho dos lançamentos espera
  // este commit e já enxerga a lista nova.
  Optional<LockedAccount> lockAccount(UUID userId, UUID id) {
    return jdbc.sql(
            "SELECT tipo, arquivada FROM conta WHERE usuario_id = :userId AND id = :id FOR UPDATE")
        .param("userId", userId)
        .param("id", id)
        .query(
            (rs, row) ->
                new LockedAccount(
                    AccountType.fromValue(rs.getString("tipo")), rs.getBoolean("arquivada")))
        .optional();
  }

  record LockedAccount(AccountType type, boolean archived) {}

  // Quantas das categorias são despesa do dono. Só dá a mensagem certa: a FK composta da V12 é
  // quem segura a corrida.
  long countExpenseCategories(UUID userId, List<UUID> ids) {
    if (ids.isEmpty()) {
      return 0;
    }
    return jdbc.sql(
            "SELECT count(*) FROM categoria"
                + " WHERE usuario_id = :userId AND tipo = 'despesa' AND id IN (:ids)")
        .param("userId", userId)
        .param("ids", ids)
        .query(Long.class)
        .single();
  }

  // tipo_conta e tipo_categoria são colunas geradas: o INSERT não as cita. O SELECT na categoria
  // amarra o dono de novo, além da FK.
  void insertPermitted(UUID userId, UUID accountId, List<UUID> ids) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.sql(
            "INSERT INTO conta_categoria_permitida (conta_id, categoria_id, usuario_id)"
                + " SELECT :accountId, id, :userId FROM categoria"
                + " WHERE usuario_id = :userId AND tipo = 'despesa' AND id IN (:ids)")
        .param("accountId", accountId)
        .param("userId", userId)
        .param("ids", ids)
        .update();
  }

  void deletePermitted(UUID userId, UUID accountId) {
    jdbc.sql(
            "DELETE FROM conta_categoria_permitida"
                + " WHERE usuario_id = :userId AND conta_id = :accountId")
        .param("userId", userId)
        .param("accountId", accountId)
        .update();
  }

  // Nulo não muda: COALESCE deixa o valor que já está.
  int updateAccount(
      UUID userId, UUID id, String name, BigDecimal initialBalance, Boolean archived) {
    return jdbc.sql(
            """
            UPDATE conta SET nome = COALESCE(:name, nome),
                             saldo_inicial = COALESCE(:initialBalance, saldo_inicial),
                             arquivada = COALESCE(:archived, arquivada)
            WHERE usuario_id = :userId AND id = :id
            """)
        .param("userId", userId)
        .param("id", id)
        .param("name", name)
        .param("initialBalance", initialBalance)
        .param("archived", archived)
        .update();
  }

  void updateCard(UUID userId, UUID accountId, BigDecimal limit, UUID paymentAccountId) {
    jdbc.sql(
            """
            UPDATE cartao SET limite = COALESCE(:limit, limite),
                              conta_pagamento_id = COALESCE(:paymentAccountId, conta_pagamento_id)
            WHERE usuario_id = :userId AND conta_id = :accountId
            """)
        .param("userId", userId)
        .param("accountId", accountId)
        .param("limit", limit)
        .param("paymentAccountId", paymentAccountId)
        .update();
  }

  // tipo_conta e pagamento_aceito são colunas geradas: o INSERT não as cita.
  void insertCard(UUID userId, UUID accountId, CardInput card) {
    jdbc.sql(
            """
            INSERT INTO cartao (conta_id, usuario_id, limite, dia_fechamento, dia_vencimento,
                                conta_pagamento_id)
            VALUES (:accountId, :userId, :limit, :closingDay, :dueDay, :paymentAccountId)
            """)
        .param("accountId", accountId)
        .param("userId", userId)
        .param("limit", card.limit())
        .param("closingDay", card.closingDay())
        .param("dueDay", card.dueDay())
        .param("paymentAccountId", card.paymentAccountId())
        .update();
  }

  private static AccountSummary map(ResultSet rs, int row) throws SQLException {
    return new AccountSummary(
        rs.getObject("id", UUID.class),
        rs.getString("nome"),
        AccountType.fromValue(rs.getString("tipo")),
        rs.getString("moeda"),
        rs.getBigDecimal("saldo_inicial"),
        rs.getBigDecimal("saldo"),
        rs.getBoolean("arquivada"),
        rs.getObject("criada_em", OffsetDateTime.class).toInstant(),
        mapCard(rs),
        mapPermitted(rs),
        rs.getBigDecimal("percentual_cdi"),
        rs.getBoolean("em_investimentos"));
  }

  // Nulo fora de conta de benefício; vazio quando a conta aceita qualquer categoria.
  private static List<UUID> mapPermitted(ResultSet rs) throws SQLException {
    java.sql.Array array = rs.getArray("categorias_permitidas");
    if (array == null) {
      return null;
    }
    return List.of((UUID[]) array.getArray());
  }

  private static AccountSummary.CardSummary mapCard(ResultSet rs) throws SQLException {
    BigDecimal limit = rs.getBigDecimal("limite");
    if (limit == null) {
      return null;
    }
    return new AccountSummary.CardSummary(
        limit,
        rs.getInt("dia_fechamento"),
        rs.getInt("dia_vencimento"),
        rs.getObject("conta_pagamento_id", UUID.class));
  }

  void updateInInvestments(UUID userId, UUID accountId, boolean value) {
    jdbc.sql("UPDATE conta SET em_investimentos = :value WHERE usuario_id = :userId AND id = :id")
        .param("value", value)
        .param("userId", userId)
        .param("id", accountId)
        .update();
  }

  void updateCdiPercent(UUID userId, UUID accountId, BigDecimal percent) {
    jdbc.sql("UPDATE conta SET percentual_cdi = :percent WHERE usuario_id = :userId AND id = :id")
        .param("percent", percent)
        .param("userId", userId)
        .param("id", accountId)
        .update();
  }
}
