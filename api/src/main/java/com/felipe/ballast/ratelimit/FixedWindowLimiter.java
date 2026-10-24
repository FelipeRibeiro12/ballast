package com.felipe.ballast.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

// Janela fixa por chave, em memória (ADR-049). Uma instância só da API, então não precisa de
// estado compartilhado. A virada da janela deixa passar até o dobro do limite num instante; para
// conter flood isso basta, e o código fica pequeno.
final class FixedWindowLimiter {

  record Decision(boolean allowed, Duration retryAfter, boolean firstRefusal) {}

  private record Window(long index, int count) {}

  private final int limit;
  private final long windowMillis;
  private final int maxKeys;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  FixedWindowLimiter(int limit, Duration window, int maxKeys) {
    this.limit = limit;
    this.windowMillis = window.toMillis();
    this.maxKeys = maxKeys;
  }

  Decision tryAcquire(String key, Instant now) {
    long nowMillis = now.toEpochMilli();
    long index = Math.floorDiv(nowMillis, windowMillis);
    Duration retryAfter = Duration.ofMillis((index + 1) * windowMillis - nowMillis);
    // Tabela cheia de IPs novos é o flood espalhado: recusar a chave nova mantém a memória
    // limitada e quem já está na tabela continua sendo atendido.
    if (!windows.containsKey(key) && windows.size() >= maxKeys) {
      windows.values().removeIf(window -> window.index() < index);
      if (windows.size() >= maxKeys) {
        return new Decision(false, retryAfter, false);
      }
    }
    // Para em limit + 2: dá para saber qual foi a primeira recusa sem o contador estourar.
    Window updated =
        windows.compute(
            key,
            (k, current) ->
                current == null || current.index() != index
                    ? new Window(index, 1)
                    : new Window(index, Math.min(current.count() + 1, limit + 2)));
    if (updated.count() <= limit) {
      return new Decision(true, Duration.ZERO, false);
    }
    return new Decision(false, retryAfter, updated.count() == limit + 1);
  }

  int size() {
    return windows.size();
  }

  void clear() {
    windows.clear();
  }
}
