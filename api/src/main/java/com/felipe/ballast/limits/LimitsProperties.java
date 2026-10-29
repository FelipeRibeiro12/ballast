package com.felipe.ballast.limits;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Tetos por usuário (ADR-055). Os padrões são largos: ninguém chega neles usando o app de verdade,
// eles só seguram script. O .env.example deixa as chaves em branco e variável vazia chega como
// nulo, então nulo vale o padrão. Fora da faixa derruba a subida.
@ConfigurationProperties("ballast.limits")
public record LimitsProperties(
    Integer accounts,
    Integer activeRecurrences,
    Integer installmentPurchases,
    Integer fixedIncomeTitles,
    Integer cryptoMovements,
    Integer cryptoAssets,
    Integer quotes,
    Integer categories,
    Integer thirdParties,
    Integer balanceViews,
    Integer pendingImports,
    Integer csvMappings,
    Integer wallets,
    Duration accountDeleteTimeout) {

  static final int MAX = 1_000_000;
  static final Duration DEFAULT_DELETE_TIMEOUT = Duration.ofSeconds(15);

  public LimitsProperties {
    accounts = valid("accounts", accounts, 100);
    activeRecurrences = valid("active-recurrences", activeRecurrences, 200);
    installmentPurchases = valid("installment-purchases", installmentPurchases, 1_000);
    fixedIncomeTitles = valid("fixed-income-titles", fixedIncomeTitles, 500);
    cryptoMovements = valid("crypto-movements", cryptoMovements, 20_000);
    cryptoAssets = valid("crypto-assets", cryptoAssets, 200);
    quotes = valid("quotes", quotes, 100_000);
    categories = valid("categories", categories, 20);
    thirdParties = valid("third-parties", thirdParties, 50);
    balanceViews = valid("balance-views", balanceViews, 10);
    pendingImports = valid("pending-imports", pendingImports, 20);
    csvMappings = valid("csv-mappings", csvMappings, 20);
    wallets = valid("wallets", wallets, 20);
    if (accountDeleteTimeout == null) {
      accountDeleteTimeout = DEFAULT_DELETE_TIMEOUT;
    }
    if (accountDeleteTimeout.isNegative()
        || accountDeleteTimeout.isZero()
        || accountDeleteTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException(
          "ballast.limits.account-delete-timeout deve ficar entre 1 ms e 5 minutos");
    }
  }

  public static LimitsProperties defaults() {
    return new LimitsProperties(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private static int valid(String name, Integer value, int fallback) {
    if (value == null) {
      return fallback;
    }
    if (value < 1 || value > MAX) {
      throw new IllegalArgumentException(
          "ballast.limits." + name + " deve ser um inteiro entre 1 e " + MAX);
    }
    return value;
  }
}
