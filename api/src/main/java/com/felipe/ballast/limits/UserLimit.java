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
      LimitsProperties::quotes),
  // Outros e Outras receitas são protegidas e ficam fora da conta (ADR-064).
  CATEGORIES(
      "categorias",
      "SELECT count(*) FROM categoria WHERE usuario_id = :userId"
          + " AND semente IS DISTINCT FROM 'outros' AND semente IS DISTINCT FROM 'outras_receitas'",
      LimitsProperties::categories),
  THIRD_PARTIES(
      "terceiros",
      "SELECT count(*) FROM terceiro WHERE usuario_id = :userId",
      LimitsProperties::thirdParties),
  BALANCE_VIEWS(
      "visões de saldo",
      "SELECT count(*) FROM visao_saldo WHERE usuario_id = :userId",
      LimitsProperties::balanceViews),
  // Só a pendente conta: a confirmada é histórico e libera a vaga (ADR-069).
  PENDING_IMPORTS(
      "importações pendentes",
      "SELECT count(*) FROM importacao WHERE usuario_id = :userId AND status = 'pendente'",
      LimitsProperties::pendingImports),
  CSV_MAPPINGS(
      "mapeamentos de CSV",
      "SELECT count(*) FROM mapeamento_csv WHERE usuario_id = :userId",
      LimitsProperties::csvMappings),
  WALLETS(
      "carteiras",
      "SELECT count(*) FROM carteira_observada WHERE usuario_id = :userId",
      LimitsProperties::wallets);

  final String label;
  final String countSql;
  final ToIntFunction<LimitsProperties> max;

  UserLimit(String label, String countSql, ToIntFunction<LimitsProperties> max) {
    this.label = label;
    this.countSql = countSql;
    this.max = max;
  }
}
