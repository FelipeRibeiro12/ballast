package com.felipe.ballast.wallets;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Chave da Zerion (ADR-081). Sem ela NFT e DeFi ficam desligados e o resto da carteira segue.
@ConfigurationProperties("ballast.zerion")
public record ZerionProperties(String apiKey, String url) {

  static final String DEFAULT_URL = "https://api.zerion.io/v1";

  public ZerionProperties {
    url = url == null || url.isBlank() ? DEFAULT_URL : url;
  }

  // O record imprimiria a chave.
  @Override
  public String toString() {
    return "ZerionProperties[configured=" + hasKey() + "]";
  }

  boolean hasKey() {
    return apiKey != null && !apiKey.isBlank();
  }
}
