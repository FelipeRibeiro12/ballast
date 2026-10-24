package com.felipe.ballast.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class OriginPropertiesTest {

  @Test
  void normalizesCaseAndDropsBlanks() {
    var properties = new OriginProperties(List.of(" HTTPS://App.Exemplo.Test ", "", "  "));

    assertThat(properties.allowed()).containsExactly("https://app.exemplo.test");
  }

  @Test
  void refusesAnEntryThatIsNotAnOrigin() {
    assertThatThrownBy(() -> new OriginProperties(List.of("https://app.exemplo.test/")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OriginProperties(List.of("app.exemplo.test")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
