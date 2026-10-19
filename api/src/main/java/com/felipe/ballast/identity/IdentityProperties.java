package com.felipe.ballast.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ballast")
public record IdentityProperties(
    // Texto, não boolean: variável de ambiente vazia (.env.example) tem que valer como fechado.
    @DefaultValue("false") String registrationOpen, @DefaultValue Password password) {

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
