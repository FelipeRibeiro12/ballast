package com.felipe.ballast.wallets;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;

// Sem BALLAST_POSSE_DOMAIN a assinatura valeria para um domínio que ninguém escolheu. Como o
// COOKIE_SECURE, só APP_ENV=development aceita o padrão; APP_ENV ausente conta como produção. A
// checagem roda antes de qualquer bean para a subida falhar antes do Flyway tocar no banco.
// Registrado em META-INF/spring.factories.
public class PosseEnvironmentValidator implements EnvironmentPostProcessor {

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    Binder binder = Binder.get(environment);
    PosseProperties posse =
        binder
            .bind("ballast.posse", PosseProperties.class)
            .orElseGet(() -> new PosseProperties(""));
    String appEnv = binder.bind("ballast.app-env", String.class).orElse("");
    if (posse.isBlank() && !appEnv.equals("development")) {
      throw new IllegalStateException(
          "BALLAST_POSSE_DOMAIN é obrigatório fora de APP_ENV=development");
    }
  }
}
