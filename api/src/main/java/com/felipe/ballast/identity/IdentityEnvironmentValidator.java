package com.felipe.ballast.identity;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;

// Bind das propriedades antes de qualquer bean: o construtor de IdentityProperties recusa
// configuração insegura, e isso precisa acontecer antes de o Flyway tocar no banco. Registrado em
// META-INF/spring.factories.
public class IdentityEnvironmentValidator implements EnvironmentPostProcessor {

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    Binder.get(environment).bind("ballast", IdentityProperties.class);
  }
}
