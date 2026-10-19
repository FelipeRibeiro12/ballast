package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CookieSecureConfigTest {

  private static IdentityProperties with(String cookieSecure, String appEnv) {
    return new IdentityProperties(
        "false", cookieSecure, appEnv, new IdentityProperties.Password(8, 1, 1));
  }

  // Ausente ou vazio vale true: o .env.example deixa a chave em branco.
  @ParameterizedTest(name = "COOKIE_SECURE=''{0}'' APP_ENV=''{1}'' vale {2}")
  @CsvSource(
      value = {
        "'',     '',            true",
        "'',     development,   true",
        "true,   production,    true",
        "true,   '',            true",
        "true,   development,   true",
        "false,  development,   false",
      })
  void acceptsTheCombinationsThatKeepOrDropSecureOnPurpose(
      String cookieSecure, String appEnv, boolean expected) {
    assertThat(with(cookieSecure, appEnv).isCookieSecure()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "COOKIE_SECURE=''{0}'' APP_ENV=''{1}'' recusa")
  @CsvSource(
      value = {
        "false,  '',            ",
        "false,  production,    ",
        "false,  staging,       ",
        "false,  Development,   ",
        "talvez, development,   ",
        "0,      development,   ",
        "FALSE,  development,   ",
      })
  void refusesFalseOutsideDevelopmentAndAnythingThatIsNotTrueOrFalse(
      String cookieSecure, String appEnv) {
    assertThatThrownBy(() -> with(cookieSecure, appEnv))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void errorMessageNeverEchoesTheValue() {
    assertThatThrownBy(() -> with("segredo-que-nao-devia-aparecer", "development"))
        .hasMessageNotContaining("segredo-que-nao-devia-aparecer");
  }

  @Test
  void theApplicationRefusesToStartWithFalseOutsideDevelopment() {
    new ApplicationContextRunner()
        .withUserConfiguration(OnlyProperties.class)
        .withPropertyValues("ballast.cookie-secure=false")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void theApplicationStartsWithFalseInDevelopment() {
    new ApplicationContextRunner()
        .withUserConfiguration(OnlyProperties.class)
        .withPropertyValues("ballast.cookie-secure=false", "ballast.app-env=development")
        .run(
            context ->
                assertThat(context.getBean(IdentityProperties.class).isCookieSecure()).isFalse());
  }

  @Test
  void defaultIsSecure() {
    new ApplicationContextRunner()
        .withUserConfiguration(OnlyProperties.class)
        .run(
            context ->
                assertThat(context.getBean(IdentityProperties.class).isCookieSecure()).isTrue());
  }

  @Configuration
  @EnableConfigurationProperties(IdentityProperties.class)
  static class OnlyProperties {}
}
