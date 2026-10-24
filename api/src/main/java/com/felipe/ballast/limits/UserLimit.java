package com.felipe.ballast.limits;

import java.util.function.ToIntFunction;

// Cada teto com o texto da mensagem e a contagem do que ele conta. O SQL é constante nossa e só o
// usuario_id entra como parâmetro. Toda contagem filtra por usuario_id: o teto nunca é global.
public enum UserLimit {
  ACCOUNTS(
      "contas",
      "SELECT count(*) FROM conta WHERE usuario_id = :userId",
      LimitsProperties::accounts),
  ACTIVE_RECURRENCES(
      "recorrências ativas",
      "SELECT count(*) FROM template_lancamento WHERE usuario_id = :userId AND ativo",
      LimitsProperties::activeRecurrences),
  // A primeira parcela é a única com parcela_num 1, então conta uma por compra.
  INSTALLMENT_PURCHASES(
      "compras parceladas",
      "SELECT count(*) FROM lancamento WHERE usuario_id = :userId AND parcela_num = 1",
      LimitsProperties::installmentPurchases),
  FIXED_INCOME_TITLES(
      "títulos de renda fixa",
      "SELECT count(*) FROM titulo_renda_fixa WHERE usuario_id = :userId",
      LimitsProperties::fixedIncomeTitles),
  CRYPTO_MOVEMENTS(
      "movimentos de cripto",
      "SELECT count(*) FROM movimento_cripto WHERE usuario_id = :userId",
      LimitsProperties::cryptoMovements),
  CRYPTO_ASSETS(
      "ativos de cripto",
      "SELECT count(*) FROM ativo_cripto WHERE usuario_id = :userId",
      LimitsProperties::cryptoAssets),
  // A PK da cotação começa em ativo_id e não há índice por usuario_id, então a contagem parte dos
  // ativos do usuário (poucos) e lê o índice de cada um. Conta também as do job.
  QUOTES(
      "cotações",
      "SELECT count(*) FROM cotacao WHERE usuario_id = :userId AND ativo_id IN"
          + " (SELECT id FROM ativo_cripto WHERE usuario_id = :userId)",
      LimitsProperties::quotes);

  final String label;
  final String countSql;
  final ToIntFunction<LimitsProperties> max;

  UserLimit(String label, String countSql, ToIntFunction<LimitsProperties> max) {
    this.label = label;
    this.countSql = countSql;
    this.max = max;
  }
}
