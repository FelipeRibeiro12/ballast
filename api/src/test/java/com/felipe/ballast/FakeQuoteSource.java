package com.felipe.ballast;

import com.felipe.ballast.crypto.AssetRef;
import com.felipe.ballast.crypto.ProviderQuote;
import com.felipe.ballast.crypto.QuoteSource;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// A CoinGecko dos testes de integração: devolve o que o teste publicou e pode fingir que caiu.
// Um bean só para todos os testes, como o FakeIndexSource; quem mexe chama reset no fim.
public class FakeQuoteSource implements QuoteSource {

  private final Map<AssetRef, ProviderQuote> published = new ConcurrentHashMap<>();
  private final List<AssetRef> requests = new CopyOnWriteArrayList<>();
  private final AtomicBoolean down = new AtomicBoolean();

  public void publish(AssetRef asset, ProviderQuote quote) {
    published.put(asset, quote);
  }

  public void goDown() {
    down.set(true);
  }

  public List<AssetRef> requests() {
    return List.copyOf(requests);
  }

  public void reset() {
    published.clear();
    requests.clear();
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

  @TestConfiguration(proxyBeanMethods = false)
  public static class Config {

    @Bean
    @Primary
    FakeQuoteSource fakeQuoteSource() {
      return new FakeQuoteSource();
    }
  }
}
