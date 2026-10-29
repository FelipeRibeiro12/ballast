package com.felipe.ballast;

import com.felipe.ballast.wallets.IndexerUnavailableException;
import com.felipe.ballast.wallets.WalletIndexer;
import com.felipe.ballast.wallets.WalletNetwork;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// O indexador dos testes de integração: devolve o saldo que o teste publicou, pode fingir que caiu
// e conta as chamadas. Nenhum teste chama a rede. Um bean só; quem mexe chama reset no fim.
public class FakeWalletIndexer implements WalletIndexer {

  private record Key(WalletNetwork network, String address) {}

  private final Map<Key, Reading> published = new ConcurrentHashMap<>();
  private final AtomicInteger calls = new AtomicInteger();
  private volatile IndexerUnavailableException.Reason failure;
  private volatile boolean configured = true;

  public void publish(WalletNetwork network, String address, Reading reading) {
    published.put(new Key(network, address), reading);
  }

  public void publish(WalletNetwork network, String address, Holding... holdings) {
    publish(network, address, new Reading(List.of(holdings), false));
  }

  public static Holding nativeHolding(String quantity) {
    return new Holding(null, "ETH", 18, new BigDecimal(quantity));
  }

  public static Holding token(String contract, String symbol, int decimals, String quantity) {
    return new Holding(contract, symbol, decimals, new BigDecimal(quantity));
  }

  public void failWith(IndexerUnavailableException.Reason reason) {
    failure = reason;
  }

  public void recover() {
    failure = null;
  }

  public int calls() {
    return calls.get();
  }

  public void reset() {
    published.clear();
    calls.set(0);
    failure = null;
    configured = true;
  }

  @Override
  public Reading read(WalletNetwork network, String address) {
    calls.incrementAndGet();
    if (failure != null) {
      throw new IndexerUnavailableException(failure);
    }
    Reading reading = published.get(new Key(network, address));
    return reading == null ? new Reading(List.of(), false) : reading;
  }

  @Override
  public boolean isConfigured() {
    return configured;
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class Config {

    @Bean
    @Primary
    FakeWalletIndexer fakeWalletIndexer() {
      return new FakeWalletIndexer();
    }
  }
}
