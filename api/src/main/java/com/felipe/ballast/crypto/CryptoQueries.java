package com.felipe.ballast.crypto;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017). Toda consulta filtra por usuario_id; as FKs compostas da V13 garantem o
// dono de conta e ativo, estas leituras só dão a mensagem certa antes de gravar.
@Repository
class CryptoQueries {

  private static final String ASSET_COLUMNS =
      "SELECT id, rede, contrato, simbolo, nome, decimais, confiavel FROM ativo_cripto";

  private static final String MOVEMENT_COLUMNS =
      "SELECT id, conta_id, ativo_id, tipo, quantidade, data, custo FROM movimento_cripto";

  private static final String SIGNED_QUANTITY =
      "CASE tipo WHEN 'compra' THEN quantidade ELSE -quantidade END";

  record Asset(
      UUID id,
      CryptoNetwork network,
      String contract,
      String symbol,
      String name,
      int decimals,
      boolean trusted) {}

  record Movement(
      UUID id,
      UUID accountId,
      UUID assetId,
      MovementType type,
      BigDecimal quantity,
      LocalDate date,
      BigDecimal cost) {}

  // price e quotedAt são nulos quando não há cotação até a data.
  record Position(
      UUID accountId,
      UUID assetId,
      BigDecimal quantity,
      BigDecimal cost,
      BigDecimal price,
      Instant quotedAt) {}

  record LockedAccount(boolean crypto, boolean archived) {}

  private final JdbcClient jdbc;

  CryptoQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // O ativo digitado nasce confiável (ADR-035); o false do DEFAULT fica para a sincronização.
  Asset insertAsset(
      UUID userId,
      CryptoNetwork network,
      String contract,
      String symbol,
      String name,
      int decimals) {
    return jdbc.sql(
            """
            INSERT INTO ativo_cripto
              (id, usuario_id, rede, contrato, simbolo, nome, decimais, confiavel)
            VALUES (:id, :userId, :network, :contract, :symbol, :name, :decimals, true)
            RETURNING id, rede, contrato, simbolo, nome, decimais, confiavel
            """)
        .param("id", newId())
        .param("userId", userId)
        .param("network", network.value())
        .param("contract", contract)
        .param("symbol", symbol)
        .param("name", name)
        .param("decimals", decimals)
        .query(CryptoQueries::mapAsset)
        .single();
  }

  List<Asset> listAssets(UUID userId) {
    return jdbc.sql(ASSET_COLUMNS + " WHERE usuario_id = :userId ORDER BY lower(simbolo), rede, id")
        .param("userId", userId)
        .query(CryptoQueries::mapAsset)
        .list();
  }

  // Vazio para ativo de outro usuário ou inexistente.
  Optional<Integer> findAssetDecimals(UUID userId, UUID assetId) {
    return jdbc.sql("SELECT decimais FROM ativo_cripto WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", assetId)
        .query(Integer.class)
        .optional();
  }

  void insertQuote(UUID userId, UUID assetId, Instant moment, BigDecimal price) {
    jdbc.sql(
            """
            INSERT INTO cotacao (usuario_id, ativo_id, momento, preco, fonte)
            VALUES (:userId, :assetId, :moment, :price, 'manual')
            """)
        .param("userId", userId)
        .param("assetId", assetId)
        .param("moment", OffsetDateTime.ofInstant(moment, ZoneOffset.UTC))
        .param("price", price)
        .update();
  }

  // Trava a linha da conta até o fim da transação, como as escritas de lançamento: duas vendas da
  // mesma conta passam uma de cada vez pela conferência da posição. Vazio para conta alheia ou
  // inexistente. Quem arquiva faz UPDATE nesta mesma linha e espera na fila.
  Optional<LockedAccount> lockAccount(UUID userId, UUID accountId) {
    return jdbc.sql(
            "SELECT tipo = 'cripto' AS cripto, arquivada FROM conta"
                + " WHERE usuario_id = :userId AND id = :id FOR UPDATE")
        .param("userId", userId)
        .param("id", accountId)
        .query((rs, row) -> new LockedAccount(rs.getBoolean("cripto"), rs.getBoolean("arquivada")))
        .optional();
  }

  Movement insertMovement(
      UUID userId,
      UUID accountId,
      UUID assetId,
      MovementType type,
      BigDecimal quantity,
      LocalDate date,
      BigDecimal cost) {
    return jdbc.sql(
            """
            INSERT INTO movimento_cripto
              (id, usuario_id, conta_id, ativo_id, tipo, quantidade, data, custo)
            VALUES (:id, :userId, :accountId, :assetId, :type, :quantity, :date, :cost)
            RETURNING id, conta_id, ativo_id, tipo, quantidade, data, custo
            """)
        .param("id", newId())
        .param("userId", userId)
        .param("accountId", accountId)
        .param("assetId", assetId)
        .param("type", type.value())
        .param("quantity", quantity)
        .param("date", date)
        .param("cost", cost)
        .query(CryptoQueries::mapMovement)
        .single();
  }

  // A soma acumulada dia a dia nunca abaixo de zero. Com ORDER BY e sem frame, a janela vai do
  // começo até o fim do dia corrente, então compra e venda do mesmo dia contam juntas.
  boolean positionNeverNegative(UUID userId, UUID accountId, UUID assetId) {
    return jdbc.sql(
            "SELECT COALESCE(MIN(posicao), 0) >= 0 FROM ("
                + " SELECT SUM("
                + SIGNED_QUANTITY
                + ") OVER (ORDER BY data) AS posicao"
                + " FROM movimento_cripto"
                + " WHERE usuario_id = :userId AND conta_id = :accountId AND ativo_id = :assetId"
                + ") s")
        .param("userId", userId)
        .param("accountId", accountId)
        .param("assetId", assetId)
        .query(Boolean.class)
        .single();
  }

  Optional<Movement> findMovement(UUID userId, UUID id) {
    return jdbc.sql(MOVEMENT_COLUMNS + " WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .query(CryptoQueries::mapMovement)
        .optional();
  }

  int deleteMovement(UUID userId, UUID id) {
    return jdbc.sql("DELETE FROM movimento_cripto WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  // accountId e assetId nulos não filtram.
  List<Movement> listMovements(UUID userId, UUID accountId, UUID assetId) {
    return jdbc.sql(
            MOVEMENT_COLUMNS
                + " WHERE usuario_id = :userId"
                + " AND (CAST(:accountId AS uuid) IS NULL OR conta_id = :accountId)"
                + " AND (CAST(:assetId AS uuid) IS NULL OR ativo_id = :assetId)"
                + " ORDER BY data DESC, criado_em DESC, id DESC")
        .param("userId", userId)
        .param("accountId", accountId)
        .param("assetId", assetId)
        .query(CryptoQueries::mapMovement)
        .list();
  }

  // Posições das contas ativas com movimento até date, com a última cotação antes de quoteLimit
  // (o começo do dia seguinte em São Paulo). A PK de cotacao serve a busca pela última de trás
  // para frente. Posição zerada fica de fora.
  List<Position> positions(UUID userId, LocalDate date, Instant quoteLimit) {
    return jdbc.sql(
            "WITH posicao AS ("
                + " SELECT conta_id, ativo_id,"
                + " SUM("
                + SIGNED_QUANTITY
                + ") AS quantidade,"
                + " SUM(CASE tipo WHEN 'compra' THEN custo ELSE -custo END) AS custo"
                + " FROM movimento_cripto"
                + " WHERE usuario_id = :userId AND data <= :date"
                + " GROUP BY conta_id, ativo_id"
                + ")"
                + " SELECT p.conta_id, p.ativo_id, p.quantidade, p.custo, q.preco, q.momento"
                + " FROM posicao p"
                + " JOIN conta c ON c.id = p.conta_id AND c.usuario_id = :userId"
                + " JOIN ativo_cripto a ON a.id = p.ativo_id AND a.usuario_id = :userId"
                + " LEFT JOIN LATERAL ("
                + "   SELECT preco, momento FROM cotacao"
                + "   WHERE ativo_id = p.ativo_id AND usuario_id = :userId AND momento < :limit"
                + "   ORDER BY momento DESC LIMIT 1"
                + " ) q ON true"
                + " WHERE NOT c.arquivada AND p.quantidade <> 0"
                + " ORDER BY lower(c.nome), c.id, lower(a.simbolo), a.rede, a.id")
        .param("userId", userId)
        .param("date", date)
        .param("limit", OffsetDateTime.ofInstant(quoteLimit, ZoneOffset.UTC))
        .query(
            (rs, row) ->
                new Position(
                    rs.getObject("conta_id", UUID.class),
                    rs.getObject("ativo_id", UUID.class),
                    rs.getBigDecimal("quantidade"),
                    rs.getBigDecimal("custo"),
                    rs.getBigDecimal("preco"),
                    instantOrNull(rs, "momento")))
        .list();
  }

  // Reais de todas as contas ativas que não são cripto até date: saldo inicial mais realizados com
  // data_caixa até ela, com o mesmo sinal das pernas de transferência do saldo de /contas.
  BigDecimal accountsBalance(UUID userId, LocalDate date) {
    return jdbc.sql(
            """
            SELECT
              (SELECT COALESCE(SUM(saldo_inicial), 0) FROM conta
               WHERE usuario_id = :userId AND NOT arquivada AND tipo <> 'cripto')
              +
              (SELECT COALESCE(SUM(CASE l.tipo
                                     WHEN 'receita' THEN l.valor
                                     WHEN 'despesa' THEN -l.valor
                                     WHEN 'transferencia' THEN
                                       CASE WHEN te.id IS NOT NULL THEN l.valor ELSE -l.valor END
                                   END), 0)
               FROM lancamento l
               JOIN conta c ON c.id = l.conta_id AND c.usuario_id = l.usuario_id
               LEFT JOIN transferencia te ON te.entrada_id = l.id AND te.usuario_id = l.usuario_id
               WHERE l.usuario_id = :userId AND l.status = 'realizado'
                 AND l.data_caixa <= :date AND NOT c.arquivada)
            """)
        .param("userId", userId)
        .param("date", date)
        .query(BigDecimal.class)
        .single();
  }

  private static UUID newId() {
    // O gerador do Hibernate não usa a sessão; é o mesmo dos @UuidGenerator das entidades.
    return UuidVersion7Strategy.INSTANCE.generateUuid(null);
  }

  private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static Asset mapAsset(ResultSet rs, int row) throws SQLException {
    return new Asset(
        rs.getObject("id", UUID.class),
        CryptoNetwork.fromValue(rs.getString("rede")),
        rs.getString("contrato"),
        rs.getString("simbolo"),
        rs.getString("nome"),
        rs.getInt("decimais"),
        rs.getBoolean("confiavel"));
  }

  private static Movement mapMovement(ResultSet rs, int row) throws SQLException {
    return new Movement(
        rs.getObject("id", UUID.class),
        rs.getObject("conta_id", UUID.class),
        rs.getObject("ativo_id", UUID.class),
        MovementType.fromValue(rs.getString("tipo")),
        rs.getBigDecimal("quantidade"),
        rs.getObject("data", LocalDate.class),
        rs.getBigDecimal("custo"));
  }
}
