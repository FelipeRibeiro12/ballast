package com.felipe.ballast.identity;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IdentityConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
