package com.felipe.ballast.web;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Origens do navegador que podem chamar a API, além da própria (Origin com o mesmo Host do
// pedido). Em produção o Vercel repassa /api, então o Origin é o domínio do app e o Host é o da
// VM: sem listar o domínio aqui, toda escrita do PWA dá 403.
@ConfigurationProperties("ballast.origins")
record OriginProperties(@DefaultValue List<String> allowed) {

  private static final Pattern ORIGIN =
      Pattern.compile("(https?)://([^/@?#\\s]+)", Pattern.CASE_INSENSITIVE);

  OriginProperties {
    allowed =
        allowed.stream()
            .map(String::strip)
            .filter(origin -> !origin.isEmpty())
            .map(OriginProperties::normalize)
            .toList();
  }

  // Devolve esquema e autoridade em minúsculas, ou lança se o texto não for uma origem.
  private static String normalize(String origin) {
    var matcher = ORIGIN.matcher(origin);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "ORIGENS_PERMITIDAS deve ter origens como https://app.exemplo.com, sem barra no fim");
    }
    return (matcher.group(1) + "://" + matcher.group(2)).toLowerCase(Locale.ROOT);
  }
}
