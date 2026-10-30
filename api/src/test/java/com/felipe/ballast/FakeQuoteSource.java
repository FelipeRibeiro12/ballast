package com.felipe.ballast;

import com.felipe.ballast.crypto.AssetRef;
import com.felipe.ballast.crypto.ProviderQuote;
import com.felipe.ballast.crypto.QuoteSource;
import com.felipe.ballast.crypto.UsdcQuote;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// A CoinGecko dos testes de integração: devolve o que o teste publicou e pode fingir que caiu.
// Um bean só para todos os testes, como o FakeIndexSource; quem mexe chama reset no fim.
public class FakeQuoteSource implements QuoteSource {

  public record HistoryRequest(AssetRef asset, Instant from, Instant to) {}

  private final Map<AssetRef, ProviderQuote> published = new ConcurrentHashMap<>();
  private final Map<AssetRef, List<ProviderQuote>> histories = new ConcurrentHashMap<>();
  private final List<AssetRef> requests = new CopyOnWriteArrayList<>();
  private final List<HistoryRequest> historyRequests = new CopyOnWriteArrayList<>();
  private volatile UsdcQuote usdc;
  private volatile List<UsdcQuote> usdcHistory = List.of();
  private final List<HistoryRequest> usdcHistoryRequests = new CopyOnWriteArrayList<>();
  private final AtomicBoolean down = new AtomicBoolean();

  public void publish(AssetRef asset, ProviderQuote quote) {
    published.put(asset, quote);
  }

  public void publishHistory(AssetRef asset, List<ProviderQuote> history) {
    histories.put(asset, List.copyOf(history));
  }

  public void publishUsdc(UsdcQuote quote) {
    usdc = quote;
  }

  public void publishUsdcHistory(List<UsdcQuote> history) {
    usdcHistory = List.copyOf(history);
  }

  public List<HistoryRequest> usdcHistoryRequests() {
    return List.copyOf(usdcHistoryRequests);
  }

  public void goDown() {
    down.set(true);
  }

  public List<AssetRef> requests() {
    return List.copyOf(requests);
  }

  public List<HistoryRequest> historyRequests() {
    return List.copyOf(historyRequests);
  }

  public void reset() {
    published.clear();
    histories.clear();
    requests.clear();
    historyRequests.clear();
    usdc = null;
    usdcHistory = List.of();
    usdcHistoryRequests.clear();
    down.set(false);
  }

  @Override
  public Map<AssetRef, ProviderQuote> fetch(Collection<AssetRef> assets) {
    requests.addAll(assets);
    if (down.get()) {
      throw new IllegalStateException("coingecko fora do ar no teste");
    }
    Map<AssetRef, ProviderQuote> quotes = new HashMap<>();
    for (AssetRef asset : assets) {
      ProviderQuote quote = published.get(asset);
      if (quote != null) {
        quotes.put(asset, quote);
      }
    }
    return quotes;
  }

  @Override
  public List<ProviderQuote> history(AssetRef asset, Instant from, Instant to) {
    historyRequests.add(new HistoryRequest(asset, from, to));
    if (down.get()) {
      throw new IllegalStateException("coingecko fora do ar no teste");
    }
    return histories.getOrDefault(asset, List.of());
  }

  @Override
  public Optional<UsdcQuote> usdcBrl() {
    if (down.get()) {
      throw new IllegalStateException("coingecko fora do ar no teste");
    }
    return Optional.ofNullable(usdc);
  }

  @Override
  public List<UsdcQuote> usdcBrlHistory(Instant from, Instant to) {
    usdcHistoryRequests.add(new HistoryRequest(null, from, to));
    if (down.get()) {
      throw new IllegalStateException("coingecko fora do ar no teste");
    }
    return usdcHistory;
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class Config {

    @Bean
    @Primary
    FakeQuoteSource fakeQuoteSource() {
      return new FakeQuoteSource();
    }
  }
}
