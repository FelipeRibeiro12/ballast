package com.felipe.ballast.wallets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PosseEnvironmentValidatorTest {

  private final PosseEnvironmentValidator validator = new PosseEnvironmentValidator();

  @Test
  void refusesToStartWithoutADomainOutsideDevelopment() {
    for (String appEnv : new String[] {null, "", "production", "staging"}) {
      MockEnvironment environment = new MockEnvironment();
      if (appEnv != null) {
        environment.setProperty("ballast.app-env", appEnv);
      }
      assertThatThrownBy(() -> validator.postProcessEnvironment(environment, null))
          .as("APP_ENV=" + appEnv)
          .hasMessageContaining("BALLAST_POSSE_DOMAIN");
    }
  }

  @Test
  void startsInDevelopmentWithoutADomainAndUsesTheViteDefault() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("ballast.app-env", "development");

    assertThatCode(() -> validator.postProcessEnvironment(environment, null))
        .doesNotThrowAnyException();
    assertThat(new PosseProperties("").effectiveDomain()).isEqualTo("localhost:5173");
    assertThat(new PosseProperties("").uri()).isEqualTo("http://localhost:5173");
  }

  @Test
  void startsInProductionWithADomain() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("ballast.app-env", "production")
            .withProperty("ballast.posse.domain", "ballast.vercel.app");

    assertThatCode(() -> validator.postProcessEnvironment(environment, null))
        .doesNotThrowAnyException();
    assertThat(new PosseProperties("ballast.vercel.app").uri())
        .isEqualTo("https://ballast.vercel.app");
  }

  @Test
  void refusesADomainThatCouldBreakTheSignedText() {
    for (String domain :
        new String[] {"a b", "ex.com\nURI: x", "https://ex.com", "ex.com/x", "-ex.com"}) {
      assertThatThrownBy(() -> new PosseProperties(domain))
          .as(domain)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
