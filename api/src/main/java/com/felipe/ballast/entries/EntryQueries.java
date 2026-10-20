package com.felipe.ballast.entries;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Leitura em SQL (ADR-017). Toda consulta filtra por usuario_id. Conta e categoria são lidas
// direto da tabela, sem depender dos pacotes accounts e categories: o dono também é garantido
// pelas FKs compostas do banco, esta leitura só dá a mensagem certa antes de gravar.
@Repository
class EntryQueries {

  // As duas junções usam os UNIQUE de saida_id e entrada_id. A direção vem da ligação (ADR-025).
  private static final String COLUMNS =
      """
      SELECT l.id, l.tipo, l.titulo, l.descricao, l.valor, l.categoria_id, l.conta_id,
             l.data_competencia, l.data_caixa, l.status, l.criado_em, l.template_id,
             l.parcela_ordem,
             COALESCE(ts.id, te.id) AS transferencia_id,
             CASE WHEN ts.id IS NOT NULL THEN 'saida'
                  WHEN te.id IS NOT NULL THEN 'entrada' END AS direcao
      FROM lancamento l
      LEFT JOIN transferencia ts ON ts.saida_id = l.id AND ts.usuario_id = l.usuario_id
      LEFT JOIN transferencia te ON te.entrada_id = l.id AND te.usuario_id = l.usuario_id
      """;

  private static final String ORDER = " ORDER BY l.data_caixa DESC, l.criado_em DESC, l.id DESC";

  // 9007199254740991 centavos em reais. Comparado com compareTo: a escala não conta.
  private static final BigDecimal BALANCE_LIMIT = new BigDecimal("90071992547409.91");

  private final JdbcClient jdbc;

  EntryQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<EntrySummary> find(UUID userId, UUID id) {
    return jdbc.sql(COLUMNS + "WHERE l.usuario_id = :userId AND l.id = :id")
        .param("userId", userId)
        .param("id", id)
        .query(EntryQueries::map)
        .optional();
  }

  // Período por data_caixa, pontas incluídas. accountId nulo não filtra por conta.
  List<EntrySummary> list(UUID userId, LocalDate from, LocalDate to, UUID accountId) {
    String where = "WHERE l.usuario_id = :userId AND l.data_caixa BETWEEN :from AND :to";
    if (accountId == null) {
      return jdbc.sql(COLUMNS + where + ORDER)
          .param("userId", userId)
          .param("from", from)
          .param("to", to)
          .query(EntryQueries::map)
          .list();
    }
    return jdbc.sql(COLUMNS + where + " AND l.conta_id = :accountId" + ORDER)
        .param("userId", userId)
        .param("from", from)
        .param("to", to)
        .param("accountId", accountId)
        .query(EntryQueries::map)
        .list();
  }

  // Trava a linha da conta até o fim da transação. Vazio quando a conta não existe ou é de outro
  // usuário; senão, se está arquivada. Precisa rodar dentro de uma transação: fora dela a trava
  // solta no mesmo instante. Quem arquiva faz UPDATE nesta mesma linha e espera na fila.
  Optional<Boolean> lockAccount(UUID userId, UUID accountId) {
    return jdbc.sql(
            "SELECT arquivada FROM conta WHERE usuario_id = :userId AND id = :id FOR UPDATE")
        .param("userId", userId)
        .param("id", accountId)
        .query(Boolean.class)
        .optional();
  }

  // Fechamento e vencimento do cartão da conta; vazio quando a conta não é de cartão. Os dois dias
  // não mudam depois que o cartão nasce (ADR-031), então não precisa travar nada para ler.
  Optional<CardBilling> findCardBilling(UUID userId, UUID accountId) {
    return jdbc.sql(
            "SELECT dia_fechamento, dia_vencimento FROM cartao"
                + " WHERE usuario_id = :userId AND conta_id = :id")
        .param("userId", userId)
        .param("id", accountId)
        .query(
            (rs, row) -> new CardBilling(rs.getInt("dia_fechamento"), rs.getInt("dia_vencimento")))
        .optional();
  }

  record LockedEntry(UUID accountId, String status, UUID templateId) {}

  // Trava o lançamento do dono e devolve onde ele está agora. Vazio para lançamento alheio ou
  // inexistente. Segura o PUT, o PATCH e o DELETE do mesmo lançamento um atrás do outro, para a
  // conta lida aqui não mudar antes de ela ser travada.
  Optional<LockedEntry> lockEntry(UUID userId, UUID entryId) {
    return jdbc.sql(
            "SELECT conta_id, status, template_id FROM lancamento"
                + " WHERE usuario_id = :userId AND id = :id FOR UPDATE")
        .param("userId", userId)
        .param("id", entryId)
        .query(
            (rs, row) ->
                new LockedEntry(
                    rs.getObject("conta_id", UUID.class),
                    rs.getString("status"),
                    rs.getObject("template_id", UUID.class)))
        .optional();
  }

  // Só sai de previsto. desgarrado acompanha para a ocorrência de template: confirmar ou cancelar
  // é o usuário assumindo a linha, e a marca fica certa mesmo que alguém passe a apagar por status.
  // Valor e data nulos mantêm o que está gravado; ocorrencia_data nunca entra aqui, a chave da
  // regra continua a original. A data vira a competência e cashDate vira a caixa (igual à data fora
  // de cartão, o vencimento da fatura em cartão). O CAST dá tipo ao parâmetro nulo.
  int changeStatus(
      UUID userId,
      UUID entryId,
      TargetStatus target,
      BigDecimal amount,
      LocalDate date,
      LocalDate cashDate) {
    return jdbc.sql(
            """
            UPDATE lancamento
            SET status = :status, desgarrado = (template_id IS NOT NULL),
                valor = COALESCE(CAST(:amount AS numeric), valor),
                data_competencia = COALESCE(CAST(:date AS date), data_competencia),
                data_caixa = COALESCE(CAST(:cashDate AS date), data_caixa)
            WHERE usuario_id = :userId AND id = :id AND status = 'previsto'
            """)
        .param("status", target.value())
        .param("amount", amount)
        .param("date", date)
        .param("cashDate", cashDate)
        .param("userId", userId)
        .param("id", entryId)
        .update();
  }

  // Previstas com data_caixa antes de hoje, da mais antiga para a mais nova. Pede uma a mais que o
  // limite para o serviço saber se houve corte sem um segundo COUNT.
  List<EntrySummary> listOverdue(UUID userId, LocalDate today, int limit) {
    return jdbc.sql(
            COLUMNS
                + "WHERE l.usuario_id = :userId AND l.status = 'previsto' AND l.data_caixa < :today"
                + " ORDER BY l.data_caixa, l.id LIMIT :limit")
        .param("userId", userId)
        .param("today", today)
        .param("limit", limit)
        .query(EntryQueries::map)
        .list();
  }

  // Editar uma ocorrência de template a tira do controle da regra. Sem template, não faz nada.
  void markDetached(UUID userId, UUID entryId) {
    jdbc.sql(
            "UPDATE lancamento SET desgarrado = true"
                + " WHERE usuario_id = :userId AND id = :id AND template_id IS NOT NULL")
        .param("userId", userId)
        .param("id", entryId)
        .update();
  }

  // Perna de transferência não se edita nem se apaga por /lancamentos. Roda depois de travar o
  // lançamento: o DELETE da transferência também trava as pernas, então só um dos dois vence.
  boolean isTransferLeg(UUID userId, UUID entryId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM transferencia WHERE usuario_id = :userId"
                + " AND (saida_id = :id OR entrada_id = :id))")
        .param("userId", userId)
        .param("id", entryId)
        .query(Boolean.class)
        .single();
  }

  // Saldo atual da conta na transação em curso (ADR-024), em reais: inicial mais receitas menos
  // despesas, mais entradas menos saídas de transferência, só realizados. Mesma conta do
  // AccountQueries, para uma conta só. Sem ligação de entrada, a perna é saída.
  BigDecimal balance(UUID userId, UUID accountId) {
    return jdbc.sql(
            """
            SELECT c.saldo_inicial + COALESCE(
                     (SELECT SUM(CASE l.tipo
                                   WHEN 'receita' THEN l.valor
                                   WHEN 'despesa' THEN -l.valor
                                   WHEN 'transferencia' THEN
                                     CASE WHEN te.id IS NOT NULL THEN l.valor ELSE -l.valor END
                                 END)
                      FROM lancamento l
                      LEFT JOIN transferencia te
                        ON te.entrada_id = l.id AND te.usuario_id = l.usuario_id
                      WHERE l.usuario_id = :userId AND l.conta_id = :id
                        AND l.status = 'realizado'), 0)
            FROM conta c
            WHERE c.usuario_id = :userId AND c.id = :id
            """)
        .param("userId", userId)
        .param("id", accountId)
        .query(BigDecimal.class)
        .single();
  }

  // Lê o saldo depois da escrita, ainda dentro da transação e com a conta travada: ninguém mais
  // muda o saldo entre esta leitura e o commit.
  boolean balanceFits(UUID userId, UUID accountId) {
    return balance(userId, accountId).abs().compareTo(BALANCE_LIMIT) <= 0;
  }

  // Confere dono e tipo juntos: categoria de receita não serve para despesa.
  boolean categoryExists(UUID userId, UUID categoryId, EntryType type) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM categoria WHERE usuario_id = :userId AND id = :id"
                + " AND tipo = :type)")
        .param("userId", userId)
        .param("id", categoryId)
        .param("type", type.value())
        .query(Boolean.class)
        .single();
  }

  private static EntrySummary map(ResultSet rs, int row) throws SQLException {
    return new EntrySummary(
        rs.getObject("id", UUID.class),
        EntryType.fromValue(rs.getString("tipo")),
        rs.getString("titulo"),
        rs.getString("descricao"),
        rs.getBigDecimal("valor"),
        rs.getObject("categoria_id", UUID.class),
        rs.getObject("conta_id", UUID.class),
        rs.getObject("data_competencia", LocalDate.class),
        rs.getObject("data_caixa", LocalDate.class),
        rs.getString("status"),
        rs.getObject("criado_em", OffsetDateTime.class).toInstant(),
        rs.getObject("transferencia_id", UUID.class),
        EntryDirection.fromValue(rs.getString("direcao")),
        rs.getObject("template_id", UUID.class),
        rs.getObject("parcela_ordem", Integer.class));
  }
}
