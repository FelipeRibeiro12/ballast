package com.felipe.ballast;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// Relógio da aplicação nos testes de integração. Sem ajuste anda com o relógio do sistema; o teste
// que precisa de tempo parado chama freeze e devolve com reset (o atraso de login chega a 15 min
// e dormir não é opção). Um só bean para todos os testes: o contexto continua reaproveitado.
public class TestClock extends Clock {

  private final AtomicReference<Instant> frozen = new AtomicReference<>();

  public void freeze(Instant instant) {
    frozen.set(instant);
  }

  public void advance(Duration duration) {
    frozen.updateAndGet(current -> current.plus(duration));
  }

  public void reset() {
    frozen.set(null);
  }

  @Override
  public Instant instant() {
    Instant current = frozen.get();
    return current != null ? current : Instant.now();
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class Config {

    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }
}
