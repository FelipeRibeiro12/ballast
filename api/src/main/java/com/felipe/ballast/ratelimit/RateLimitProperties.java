package com.felipe.ballast.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Os padrões são os de produção (ADR-049). Uma pessoa usando o app fica longe de 600 por minuto:
// a tela mais pesada, o Início, faz uns 10 pedidos.
@ConfigurationProperties("ballast.rate-limit")
record RateLimitProperties(
    @DefaultValue("600") int generalPerMinute,
    @DefaultValue("10") int loginPerMinute,
    @DefaultValue("5") int registrationPerHour,
    // Login e cadastro somados, de todos os IPs: cada um gasta 64 MiB de argon2.
    @DefaultValue("60") int authPerMinute,
    // Upload e confirmação de importação, por IP: cada uma lê e grava centenas de linhas.
    @DefaultValue("10") int importsPerMinute,
    // Uns 100 bytes por IP: 20 mil cabem em 2 MB.
    @DefaultValue("20000") int maxClients) {

  RateLimitProperties {
    if (generalPerMinute < 1
        || loginPerMinute < 1
        || registrationPerHour < 1
        || authPerMinute < 1
        || importsPerMinute < 1
        || maxClients < 1) {
      throw new IllegalArgumentException("os limites de ballast.rate-limit devem ser positivos");
    }
  }
}
