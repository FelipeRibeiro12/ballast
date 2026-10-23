package com.felipe.ballast.crypto;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// baseUrl é a raiz da API v3 da CoinGecko e apiKey a chave do plano Demo (ADR-040). Sem chave o
// job não chama nada. Os testes de integração desligam o job.
@ConfigurationProperties("ballast.cotacoes")
public record QuotesProperties(
    String baseUrl, String apiKey, boolean jobEnabled, Duration interval) {

  static final String DEFAULT_BASE_URL = "https://api.coingecko.com/api/v3";

  // O .env.example deixa COINGECKO_API_URL em branco, e variável vazia vence o padrão do yml.
  public QuotesProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = DEFAULT_BASE_URL;
    }
  }

  boolean hasKey() {
    return apiKey != null && !apiKey.isBlank();
  }
}
