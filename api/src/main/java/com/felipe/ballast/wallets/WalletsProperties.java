package com.felipe.ballast.wallets;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// apiKey é a chave da Alchemy (ADR-071); sem ela o job não roda e a leitura manual responde
// sem_chave. As URLs são a raiz de cada rede, sem a chave: o cliente acrescenta /v2/{chave} na
// hora da chamada, então só ALCHEMY_API_KEY basta e uma URL explícita (outro provedor ou um
// proxy) vence o padrão. firstReadEnabled liga a leitura logo depois do cadastro da carteira.
@ConfigurationProperties("ballast.carteiras")
public record WalletsProperties(
    String apiKey,
    String ethereumUrl,
    String baseUrl,
    String solanaUrl,
    boolean jobEnabled,
    boolean firstReadEnabled,
    Duration interval,
    Duration pause) {

  static final String DEFAULT_ETHEREUM_URL = "https://eth-mainnet.g.alchemy.com";
  static final String DEFAULT_BASE_URL = "https://base-mainnet.g.alchemy.com";
  static final String DEFAULT_SOLANA_URL = "https://solana-mainnet.g.alchemy.com";

  public WalletsProperties {
    ethereumUrl = blank(ethereumUrl) ? DEFAULT_ETHEREUM_URL : ethereumUrl;
    baseUrl = blank(baseUrl) ? DEFAULT_BASE_URL : baseUrl;
    solanaUrl = blank(solanaUrl) ? DEFAULT_SOLANA_URL : solanaUrl;
    interval = interval == null ? Duration.ofHours(6) : interval;
    pause = pause == null ? Duration.ofSeconds(1) : pause;
  }

  // O record imprimiria a chave; nada na aplicação deve logar este objeto, mas o toString não
  // pode ser o ponto fraco.
  @Override
  public String toString() {
    return "WalletsProperties[jobEnabled=" + jobEnabled + "]";
  }

  boolean hasKey() {
    return !blank(apiKey);
  }

  String urlOf(WalletNetwork network) {
    return switch (network) {
      case ETHEREUM -> ethereumUrl;
      case BASE -> baseUrl;
      case SOLANA -> solanaUrl;
    };
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
