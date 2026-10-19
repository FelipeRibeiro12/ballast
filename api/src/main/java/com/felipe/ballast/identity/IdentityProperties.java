package com.felipe.ballast.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ballast")
public record IdentityProperties(
    // Texto, não boolean: variável de ambiente vazia (.env.example) tem que valer como fechado.
    @DefaultValue("false") String registrationOpen,
    @DefaultValue("") String cookieSecure,
    @DefaultValue("") String appEnv,
    @DefaultValue Password password) {

  // Secure fica ligado por padrão. Desligar só é aceito com APP_ENV=development explícito, para
  // um .env de desenvolvimento esquecido em produção derrubar a subida em vez de mandar o
  // cookie de sessão por HTTP. APP_ENV ausente conta como produção.
  public IdentityProperties {
    if (!cookieSecure.isEmpty() && !cookieSecure.equals("true") && !cookieSecure.equals("false")) {
      throw new IllegalArgumentException("COOKIE_SECURE deve ser true ou false");
    }
    if (cookieSecure.equals("false") && !appEnv.equals("development")) {
      throw new IllegalArgumentException("COOKIE_SECURE=false só é aceito com APP_ENV=development");
    }
  }

  public boolean isCookieSecure() {
    return !cookieSecure.equals("false");
  }

  // Mesma leitura do @ConditionalOnProperty do controller de cadastro.
  public boolean isRegistrationOpen() {
    return "true".equalsIgnoreCase(registrationOpen);
  }

  // Padrões de produção: 64 MiB e 3 iterações deixam cada tentativa offline cara em GPU
  // sem estourar a memória de uma instância pequena; 2 de paralelismo para 2 núcleos.
  public record Password(
      @DefaultValue("65536") int memoryKib,
      @DefaultValue("3") int iterations,
      @DefaultValue("2") int parallelism) {}
}
