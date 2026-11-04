package com.felipe.ballast.wallets;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// A Zerion dos testes que precisam dela configurada. Responde o que o teste publicou, ou cai do
// jeito que o provedor real cai: exceção ou vazio.
public class FakePositionsProvider implements PositionsProvider {

  public enum Mode {
    OK,
    THROW,
    EMPTY
  }

  private final AtomicReference<List<Position>> answer = new AtomicReference<>(List.of());
  private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.OK);

  public void answer(List<Position> positions) {
    answer.set(positions);
  }

  public void mode(Mode next) {
    mode.set(next);
  }

  public void reset() {
    answer.set(List.of());
    mode.set(Mode.OK);
  }

  @Override
  public boolean isConfigured() {
    return true;
  }

  @Override
  public Optional<List<Position>> read(WalletNetwork network, String address) {
    return switch (mode.get()) {
      case OK -> Optional.of(answer.get());
      case THROW -> throw new IllegalStateException("zerion fora do ar");
      case EMPTY -> Optional.empty();
    };
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class Config {

    @Bean
    @Primary
    FakePositionsProvider fakePositionsProvider() {
      return new FakePositionsProvider();
    }
  }
}
