package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RegistrationCapParseTest {

  // O .env.example deixa a chave em branco; em branco vale o padrão.
  @Test
  void blankMeansTheDefault() {
    assertThat(RegistrationCap.parse("")).isEqualTo(RegistrationCap.DEFAULT_MAX_USERS);
    assertThat(RegistrationCap.parse("  ")).isEqualTo(RegistrationCap.DEFAULT_MAX_USERS);
    assertThat(RegistrationCap.DEFAULT_MAX_USERS).isEqualTo(20);
  }

  @Test
  void readsAPositiveNumber() {
    assertThat(RegistrationCap.parse("3")).isEqualTo(3);
  }

  @Test
  void refusesAnythingElseAtStartup() {
    for (String raw : new String[] {"0", "-1", "abc", "1.5", "99999999999"}) {
      assertThatThrownBy(() -> RegistrationCap.parse(raw))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("CADASTRO_MAX_USUARIOS deve ser um inteiro entre 1 e 1000000");
    }
  }
}
