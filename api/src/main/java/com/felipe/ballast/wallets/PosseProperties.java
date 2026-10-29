package com.felipe.ballast.wallets;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Domínio que entra na mensagem da prova de posse (ADR-072). Vem da config e nunca do cabeçalho
// Host, que o cliente controla. Vazio só é aceito em desenvolvimento (PosseEnvironmentValidator
// recusa a subida nos outros ambientes); aí vale o endereço do Vite.
@ConfigurationProperties("ballast.posse")
public record PosseProperties(String domain) {

  static final String DEV_DOMAIN = "localhost:5173";

  // Só host e porta opcional: o domínio vai numa linha da mensagem assinada, então quebra de
  // linha, espaço ou barra mudariam o texto que o usuário vê.
  private static final Pattern VALID_DOMAIN =
      Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?(:[0-9]{1,5})?");

  public PosseProperties {
    domain = domain == null ? "" : domain.strip();
    if (!domain.isEmpty() && !VALID_DOMAIN.matcher(domain).matches()) {
      throw new IllegalArgumentException("BALLAST_POSSE_DOMAIN deve ser host ou host:porta");
    }
  }

  boolean isBlank() {
    return domain.isEmpty();
  }

  String effectiveDomain() {
    return domain.isEmpty() ? DEV_DOMAIN : domain;
  }

  // O Vite serve em http; qualquer outro domínio é https.
  String uri() {
    String host = effectiveDomain();
    return (host.startsWith("localhost") ? "http://" : "https://") + host;
  }
}
