package com.felipe.ballast;

import com.felipe.ballast.fixedincome.IndexPoint;
import com.felipe.ballast.fixedincome.IndexSeries;
import com.felipe.ballast.fixedincome.IndexSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// O Banco Central dos testes de integração: devolve o que o teste publicou e pode fingir que caiu.
// Um bean só para todos os testes, como o TestClock; quem mexe chama reset no fim.
public class FakeIndexSource implements IndexSource {

  private final Map<IndexSeries, List<IndexPoint>> published = new ConcurrentHashMap<>();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final AtomicBoolean down = new AtomicBoolean();

  public void publish(IndexSeries series, IndexPoint point) {
    published.computeIfAbsent(series, key -> new CopyOnWriteArrayList<>()).add(point);
  }

  public void goDown() {
    down.set(true);
  }

  public List<String> requests() {
    return List.copyOf(requests);
  }

  public void reset() {
    published.clear();
    requests.clear();
    down.set(false);
  }

  @Override
  public List<IndexPoint> fetch(IndexSeries series, LocalDate from, LocalDate to) {
    requests.add(series.value() + " " + from + " " + to);
    if (down.get()) {
      throw new IllegalStateException("banco central fora do ar no teste");
    }
    List<IndexPoint> points = new ArrayList<>();
    for (IndexPoint point : published.getOrDefault(series, List.of())) {
      if (!point.date().isBefore(from) && !point.date().isAfter(to)) {
        points.add(point);
      }
    }
    return points;
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class Config {

    @Bean
    @Primary
    FakeIndexSource fakeIndexSource() {
      return new FakeIndexSource();
    }
  }
}
