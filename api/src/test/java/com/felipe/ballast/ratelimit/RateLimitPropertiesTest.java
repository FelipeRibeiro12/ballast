package com.felipe.ballast.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RateLimitPropertiesTest {

  @Test
  void defaultsAreTheProductionOnes() {
    RateLimitProperties defaults =
        new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("ballast.rate-limit", RateLimitProperties.class);

    assertThat(defaults).isEqualTo(new RateLimitProperties(600, 10, 5, 60, 20000));
  }

  @Test
  void refusesZeroOrNegative() {
    assertThatThrownBy(() -> new RateLimitProperties(600, 0, 5, 60, 20000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RateLimitProperties(600, 10, 5, 60, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
