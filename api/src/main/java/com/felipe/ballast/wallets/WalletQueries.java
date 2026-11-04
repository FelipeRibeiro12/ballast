package com.felipe.ballast.wallets;

import com.felipe.ballast.crypto.CryptoNetwork;
import com.felipe.ballast.crypto.DustCutoff;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017). Toda consulta filtra por usuario_id; as FKs compostas da V36 garantem o
// dono da conta e da carteira, estas leituras só dão a mensagem certa antes de gravar.
@Repository
class WalletQueries {

  record Wallet(
      UUID id,
      UUID accountId,
      WalletNetwork network,
      String address,
      String label,
      Instant verifiedAt,
      Instant syncedAt,
      Instant attemptedAt,
      String lastError) {}

  record Balance(
      UUID assetId,
      String symbol,
      String contract,
      BigDecimal quantity,
      boolean trusted,
      boolean costPending) {}

  record PositionRow(
      UUID id,
      String kind,
      String category,
      String protocol,
      String name,
      String detail,
      BigDecimal quantity,
      Long valueUsdCents,
      Long change24hUsdCents) {}

  record SummaryToken(
      UUID walletId, BigDecimal quantity, BigDecimal priceUsd, BigDecimal priceAgoUsd) {}

  record SummaryDefi(UUID walletId, long valueUsdCents, Long change24hUsdCents) {}

  record LockedAccount(boolean crypto, boolean archived) {}

  record Ref(UUID userId, UUID walletId) {}

  record Asset(UUID id, boolean trusted) {}

  private static final String WALLET_COLUMNS =
      "SELECT id, conta_id, rede, endereco, rotulo, verificada_em, sincronizada_em, tentada_em,"
          + " erro_ultima_sync"
          + " FROM carteira_observada";

  private final JdbcClient jdbc;

  WalletQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Trava a linha da conta como as escritas de movimento e o DELETE de conta: a ordem é sempre
  // conta, depois carteira, então a leitura da rede não fecha ciclo com quem apaga a conta.
  Optional<LockedAccount> lockAccount(UUID userId, UUID accountId) {
    return jdbc.sql(
            "SELECT tipo = 'cripto' AS cripto, arquivada FROM conta"
                + " WHERE usuario_id = :userId AND id = :id FOR UPDATE")
        .param("userId", userId)
        .param("id", accountId)
        .query((rs, row) -> new LockedAccount(rs.getBoolean("cripto"), rs.getBoolean("arquivada")))
        .optional();
  }

  Wallet insertWallet(
      UUID userId, UUID accountId, WalletNetwork network, String address, String label) {
    UUID id = newId();
    jdbc.sql(
            """
            INSERT INTO carteira_observada (id, usuario_id, conta_id, rede, endereco, rotulo)
            VALUES (:id, :userId, :accountId, :network, :address, :label)
            """)
        .param("id", id)
        .param("userId", userId)
        .param("accountId", accountId)
        .param("network", network.value())
        .param("address", address)
        .param("label", label)
        .update();
    return findWallet(userId, id).orElseThrow();
  }

  Optional<Wallet> findWallet(UUID userId, UUID id) {
    return jdbc.sql(WALLET_COLUMNS + " WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .query(WalletQueries::mapWallet)
        .optional();
  }

  List<Wallet> listWallets(UUID userId) {
    return jdbc.sql(WALLET_COLUMNS + " WHERE usuario_id = :userId ORDER BY criado_em, id")
        .param("userId", userId)
        .query(WalletQueries::mapWallet)
        .list();
  }

  Optional<Wallet> lockWallet(UUID userId, UUID id) {
    return jdbc.sql(WALLET_COLUMNS + " WHERE usuario_id = :userId AND id = :id FOR UPDATE")
        .param("userId", userId)
        .param("id", id)
        .query(WalletQueries::mapWallet)
        .optional();
  }

  int deleteWallet(UUID userId, UUID id) {
    return jdbc.sql("DELETE FROM carteira_observada WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  void markAttempt(UUID userId, UUID id, Instant at) {
    jdbc.sql(
            "UPDATE carteira_observada SET tentada_em = :at"
                + " WHERE usuario_id = :userId AND id = :id")
        .param("at", utc(at))
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  // Leitura que deu certo: apaga o erro. Falha: guarda o código e deixa sincronizada_em como
  // estava.
  void markSuccess(UUID userId, UUID id, Instant at) {
    jdbc.sql(
            "UPDATE carteira_observada SET sincronizada_em = :at, erro_ultima_sync = NULL"
                + " WHERE usuario_id = :userId AND id = :id")
        .param("at", utc(at))
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  void markFailure(UUID userId, UUID id, String code) {
    jdbc.sql(
            "UPDATE carteira_observada SET erro_ultima_sync = :code"
                + " WHERE usuario_id = :userId AND id = :id")
        .param("code", code)
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  // O job lê as carteiras de todos os usuários: é processo do sistema, não rota, e cada leitura
  // grava só no dono. Conta arquivada não é lida.
  List<Ref> activeRefs() {
    return jdbc.sql(
            "SELECT w.usuario_id, w.id FROM carteira_observada w"
                + " JOIN conta c ON c.id = w.conta_id AND c.usuario_id = w.usuario_id"
                + " WHERE NOT c.arquivada ORDER BY w.criado_em, w.id")
        .query(
            (rs, row) ->
                new Ref(rs.getObject("usuario_id", UUID.class), rs.getObject("id", UUID.class)))
        .list();
  }

  // Saldo lido por ativo: a soma dos movimentos sincronizados da carteira, com sinal.
  Map<UUID, BigDecimal> syncedTotals(UUID userId, UUID walletId) {
    Map<UUID, BigDecimal> totals = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT ativo_id, SUM(CASE tipo WHEN 'entrada' THEN quantidade ELSE -quantidade END)"
                + " AS total FROM movimento_cripto"
                + " WHERE usuario_id = :userId AND carteira_id = :walletId GROUP BY ativo_id")
        .param("userId", userId)
        .param("walletId", walletId)
        .query(
            (rs, row) -> {
              totals.put(rs.getObject("ativo_id", UUID.class), rs.getBigDecimal("total"));
              return null;
            })
        .list();
    return totals;
  }

  List<Balance> balances(UUID userId, UUID walletId) {
    return jdbc.sql(
            """
            SELECT a.id, a.simbolo, a.contrato, a.confiavel,
                   SUM(CASE m.tipo WHEN 'entrada' THEN m.quantidade ELSE -m.quantidade END)
                     AS quantidade,
                   bool_or(m.custo IS NULL) AS custo_pendente
            FROM movimento_cripto m
            JOIN ativo_cripto a ON a.id = m.ativo_id AND a.usuario_id = m.usuario_id
            WHERE m.usuario_id = :userId AND m.carteira_id = :walletId
            GROUP BY a.id, a.simbolo, a.contrato, a.confiavel
            HAVING SUM(CASE m.tipo WHEN 'entrada' THEN m.quantidade ELSE -m.quantidade END) <> 0
            ORDER BY lower(a.simbolo), a.contrato NULLS FIRST, a.id
            """)
        .param("userId", userId)
        .param("walletId", walletId)
        .query(
            (rs, row) ->
                new Balance(
                    rs.getObject("id", UUID.class),
                    rs.getString("simbolo"),
                    rs.getString("contrato"),
                    rs.getBigDecimal("quantidade"),
                    rs.getBoolean("confiavel"),
                    rs.getBoolean("custo_pendente")))
        .list();
  }

  List<PositionRow> positions(UUID userId, UUID walletId) {
    return jdbc.sql(
            "SELECT id, tipo, categoria, protocolo, nome, detalhe, quantidade, valor_usd_centavos,"
                + " variacao_24h_usd_centavos FROM posicao_carteira WHERE usuario_id = :userId AND carteira_id = :walletId"
                + " ORDER BY tipo, valor_usd_centavos DESC NULLS LAST, nome, id")
        .param("userId", userId)
        .param("walletId", walletId)
        .query(
            (rs, row) -> {
              long cents = rs.getLong("valor_usd_centavos");
              boolean noValue = rs.wasNull();
              long change = rs.getLong("variacao_24h_usd_centavos");
              boolean noChange = rs.wasNull();
              return new PositionRow(
                  rs.getObject("id", UUID.class),
                  rs.getString("tipo"),
                  rs.getString("categoria"),
                  rs.getString("protocolo"),
                  rs.getString("nome"),
                  rs.getString("detalhe"),
                  rs.getBigDecimal("quantidade"),
                  noValue ? null : cents,
                  noChange ? null : change);
            })
        .list();
  }

  // Saldo sincronizado de ativo confiável por carteira, com o último preço e o último preço com
  // pelo menos 24 h (anterior ao último), tudo em dólar. Cotação em real (legada) volta a dólar
  // pelo dólar de hoje. Sem cotação os preços vêm nulos.
  List<SummaryToken> summaryTokens(UUID userId, Instant dayAgo) {
    return jdbc.sql(
            """
            WITH dolar AS (
              SELECT COALESCE(
                (SELECT preco FROM cotacao_usdc_brl ORDER BY momento DESC LIMIT 1),
                (SELECT valor FROM indice WHERE serie = 'dolar' ORDER BY data DESC LIMIT 1)
              ) AS valor
            ), saldo AS (
              SELECT carteira_id, ativo_id,
                     SUM(CASE tipo WHEN 'entrada' THEN quantidade ELSE -quantidade END) AS quantidade
              FROM movimento_cripto
              WHERE usuario_id = :userId AND carteira_id IS NOT NULL
              GROUP BY carteira_id, ativo_id
            )
            SELECT s.carteira_id, s.quantidade,
                   CASE q.moeda WHEN 'USD' THEN q.preco ELSE q.preco / d.valor END AS preco_usd,
                   CASE p.moeda WHEN 'USD' THEN p.preco ELSE p.preco / d.valor END AS anterior_usd
            FROM saldo s
            JOIN ativo_cripto a ON a.id = s.ativo_id AND a.usuario_id = :userId
            CROSS JOIN dolar d
            LEFT JOIN LATERAL (
              SELECT preco, moeda, momento FROM cotacao
              WHERE usuario_id = :userId AND ativo_id = s.ativo_id
              ORDER BY momento DESC LIMIT 1
            ) q ON true
            LEFT JOIN LATERAL (
              SELECT preco, moeda FROM cotacao
              WHERE usuario_id = :userId AND ativo_id = s.ativo_id
                AND momento <= :dayAgo AND momento < q.momento
              ORDER BY momento DESC LIMIT 1
            ) p ON true
            WHERE a.confiavel AND s.quantidade <> 0
            """)
        .param("userId", userId)
        .param("dayAgo", utc(dayAgo))
        .query(
            (rs, row) ->
                new SummaryToken(
                    rs.getObject("carteira_id", UUID.class),
                    rs.getBigDecimal("quantidade"),
                    rs.getBigDecimal("preco_usd"),
                    rs.getBigDecimal("anterior_usd")))
        .list();
  }

  // Posição DeFi com valor conhecido e fora da poeira, de todas as carteiras do usuário.
  List<SummaryDefi> summaryDefi(UUID userId) {
    return jdbc.sql(
            "SELECT carteira_id, valor_usd_centavos, variacao_24h_usd_centavos"
                + " FROM posicao_carteira WHERE usuario_id = :userId AND tipo = 'defi'"
                + " AND valor_usd_centavos IS NOT NULL AND abs(valor_usd_centavos) >= :dust")
        .param("userId", userId)
        .param("dust", DustCutoff.CENTS)
        .query(
            (rs, row) -> {
              long value = rs.getLong("valor_usd_centavos");
              long change = rs.getLong("variacao_24h_usd_centavos");
              boolean noChange = rs.wasNull();
              return new SummaryDefi(
                  rs.getObject("carteira_id", UUID.class), value, noChange ? null : change);
            })
        .list();
  }

  // O dólar de hoje: a última leitura do USDC em reais ou, sem ela, a última PTAX (ADR-077). Dado
  // de mercado, sem usuario_id. Vazio se não há nenhuma das duas.
  Optional<BigDecimal> latestDollar() {
    return jdbc.sql(
            "SELECT COALESCE("
                + "(SELECT preco FROM cotacao_usdc_brl ORDER BY momento DESC LIMIT 1),"
                + " (SELECT valor FROM indice WHERE serie = 'dolar' ORDER BY data DESC LIMIT 1))")
        .query(BigDecimal.class)
        .optional();
  }

  // Retrato novo no lugar do velho, na mesma transação do chamador.
  void replacePositions(UUID userId, UUID walletId, List<PositionsProvider.Position> positions) {
    jdbc.sql("DELETE FROM posicao_carteira WHERE usuario_id = :userId AND carteira_id = :walletId")
        .param("userId", userId)
        .param("walletId", walletId)
        .update();
    for (PositionsProvider.Position position : positions) {
      jdbc.sql(
              """
              INSERT INTO posicao_carteira
                (id, usuario_id, carteira_id, tipo, categoria, protocolo, nome, detalhe,
                 quantidade, valor_usd_centavos, variacao_24h_usd_centavos)
              VALUES (:id, :userId, :walletId, :kind, :category, :protocol, :name, :detail,
                      :quantity, :value, :change)
              """)
          .param("id", newId())
          .param("userId", userId)
          .param("walletId", walletId)
          .param("kind", position.kind())
          .param("category", position.category())
          .param("protocol", position.protocol())
          .param("name", position.name())
          .param("detail", position.detail())
          .param("quantity", position.quantity())
          .param("value", position.valueUsdCents())
          .param("change", position.change24hUsdCents())
          .update();
    }
  }

  Optional<Asset> findAsset(UUID userId, CryptoNetwork network, String contract) {
    return jdbc.sql(
            "SELECT id, confiavel FROM ativo_cripto WHERE usuario_id = :userId AND rede = :network"
                + " AND contrato IS NOT DISTINCT FROM CAST(:contract AS text)")
        .param("userId", userId)
        .param("network", network.value())
        .param("contract", contract)
        .query((rs, row) -> new Asset(rs.getObject("id", UUID.class), rs.getBoolean("confiavel")))
        .optional();
  }

  void trustAsset(UUID userId, UUID assetId) {
    jdbc.sql("UPDATE ativo_cripto SET confiavel = true WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", assetId)
        .update();
  }

  // O nativo da rede nasce confiável (o contrato nulo não se forja); o resto nasce false, que é o
  // DEFAULT da V13 (ADR-071).
  UUID insertAsset(
      UUID userId,
      CryptoNetwork network,
      String contract,
      String symbol,
      String name,
      int decimals,
      boolean trusted) {
    UUID id = newId();
    jdbc.sql(
            """
            INSERT INTO ativo_cripto
              (id, usuario_id, rede, contrato, simbolo, nome, decimais, confiavel)
            VALUES (:id, :userId, :network, :contract, :symbol, :name, :decimals, :trusted)
            """)
        .param("id", id)
        .param("userId", userId)
        .param("network", network.value())
        .param("contract", contract)
        .param("symbol", symbol)
        .param("name", name)
        .param("decimals", decimals)
        .param("trusted", trusted)
        .update();
    return id;
  }

  // Entrada ou saída sem custo: o dólar é informado depois, em PUT /movimentos-cripto/{id}/custo.
  void insertSyncedMovement(
      UUID userId,
      UUID accountId,
      UUID walletId,
      UUID assetId,
      String type,
      BigDecimal quantity,
      LocalDate date) {
    jdbc.sql(
            """
            INSERT INTO movimento_cripto
              (id, usuario_id, conta_id, ativo_id, tipo, quantidade, data, origem, carteira_id)
            VALUES (:id, :userId, :accountId, :assetId, :type, :quantity, :date,
                    'sincronizada', :walletId)
            """)
        .param("id", newId())
        .param("userId", userId)
        .param("accountId", accountId)
        .param("assetId", assetId)
        .param("type", type)
        .param("quantity", quantity)
        .param("date", date)
        .param("walletId", walletId)
        .update();
  }

  private static UUID newId() {
    // O gerador do Hibernate não usa a sessão; é o mesmo dos @UuidGenerator das entidades.
    return UuidVersion7Strategy.INSTANCE.generateUuid(null);
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static Wallet mapWallet(ResultSet rs, int row) throws SQLException {
    return new Wallet(
        rs.getObject("id", UUID.class),
        rs.getObject("conta_id", UUID.class),
        WalletNetwork.fromValue(rs.getString("rede")).orElseThrow(),
        rs.getString("endereco"),
        rs.getString("rotulo"),
        instantOrNull(rs, "verificada_em"),
        instantOrNull(rs, "sincronizada_em"),
        instantOrNull(rs, "tentada_em"),
        rs.getString("erro_ultima_sync"));
  }
}
