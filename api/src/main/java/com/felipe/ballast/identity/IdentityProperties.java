package com.felipe.ballast.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ballast")
public record IdentityProperties(@DefaultValue Password password) {

  // Padrões de produção: 64 MiB e 3 iterações deixam cada tentativa offline cara em GPU
  // sem estourar a memória de uma instância pequena; 2 de paralelismo para 2 núcleos.
  public record Password(
      @DefaultValue("65536") int memoryKib,
      @DefaultValue("3") int iterations,
      @DefaultValue("2") int parallelism) {}
}
