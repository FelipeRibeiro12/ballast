package com.felipe.ballast.crypto;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// API v3 da CoinGecko no plano Demo (ADR-040). Nativos em /simple/price por id da moeda; tokens em
// /simple/token_price/{plataforma} pelo contrato. Os dois respondem {"chave": {"usd": número,
// "last_updated_at": segundos}}.
@Component
class CoinGeckoQuoteSource implements QuoteSource {

  private static final Logger log = LoggerFactory.getLogger(CoinGeckoQuoteSource.class);

  // A CoinGecko fora não pode prender a thread do job.
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  // A documentação aceita mais por pedido; 100 deixa a URL curta e o pedido barato.
  private static final int CONTRACTS_PER_CALL = 100;

  // ETH na Base e na Arbitrum é o mesmo ether. Na Polygon o nativo é o POL desde 2024.
  private static final Map<CryptoNetwork, String> NATIVE_ID =
      new EnumMap<>(
          Map.of(
              CryptoNetwork.BITCOIN, "bitcoin",
              CryptoNetwork.ETHEREUM, "ethereum",
              CryptoNetwork.BASE, "ethereum",
              CryptoNetwork.ARBITRUM, "ethereum",
              CryptoNetwork.POLYGON, "polygon-ecosystem-token",
              CryptoNetwork.SOLANA, "solana"));

  private static final Map<CryptoNetwork, String> PLATFORM_ID =
      new EnumMap<>(
          Map.of(
              CryptoNetwork.ETHEREUM, "ethereum",
              CryptoNetwork.BASE, "base",
              CryptoNetwork.ARBITRUM, "arbitrum-one",
              CryptoNetwork.POLYGON, "polygon-pos",
              CryptoNetwork.SOLANA, "solana"));

  // O preço chega como número JSON. Lido como double, 0.1 + 0.2 já viria errado.
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private final RestClient client;

  @Autowired
  CoinGeckoQuoteSource(QuotesProperties properties) {
    this(withKey(withTimeouts(RestClient.builder().baseUrl(properties.baseUrl())), properties));
  }

  // O teste entrega um cliente ligado ao servidor simulado do Spring.
  CoinGeckoQuoteSource(RestClient client) {
    this.client = client;
  }

  private static RestClient.Builder withTimeouts(RestClient.Builder builder) {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    factory.setReadTimeout(TIMEOUT);
    return builder.requestFactory(factory);
  }

  // A chave vai no cabeçalho, não na URL: URL aparece em log de proxy e em mensagem de exceção.
  private static RestClient withKey(RestClient.Builder builder, QuotesProperties properties) {
    if (properties.hasKey()) {
      builder.defaultHeader("x-cg-demo-api-key", properties.apiKey());
    }
    return builder.build();
  }

  @Override
  public Map<AssetRef, ProviderQuote> fetch(Collection<AssetRef> assets) {
    Map<String, List<AssetRef>> natives = new LinkedHashMap<>();
    Map<CryptoNetwork, Set<String>> tokens = new EnumMap<>(CryptoNetwork.class);
    for (AssetRef asset : assets) {
      if (asset.contract() == null) {
        natives.computeIfAbsent(NATIVE_ID.get(asset.network()), id -> new ArrayList<>()).add(asset);
      } else if (PLATFORM_ID.containsKey(asset.network())) {
        tokens
            .computeIfAbsent(asset.network(), network -> new LinkedHashSet<>())
            .add(asset.contract());
      }
    }
    Map<AssetRef, ProviderQuote> quotes = new HashMap<>();
    if (!natives.isEmpty()) {
      JsonNode body =
          get(
              "/simple/price?ids={ids}&vs_currencies=usd&include_last_updated_at=true"
                  + "&precision=full",
              String.join(",", natives.keySet()));
      natives.forEach(
          (id, refs) -> {
            ProviderQuote quote = read(body.get(id));
            if (quote != null) {
              refs.forEach(ref -> quotes.put(ref, quote));
            }
          });
    }
    tokens.forEach(
        (network, contracts) -> {
          List<String> all = List.copyOf(contracts);
          for (int start = 0; start < all.size(); start += CONTRACTS_PER_CALL) {
            List<String> chunk =
                all.subList(start, Math.min(all.size(), start + CONTRACTS_PER_CALL));
            JsonNode body =
                get(
                    "/simple/token_price/{platform}?contract_addresses={contracts}"
                        + "&vs_currencies=usd&include_last_updated_at=true&precision=full",
                    PLATFORM_ID.get(network),
                    String.join(",", chunk));
            Map<String, JsonNode> byContract = new HashMap<>();
            body.properties()
                .forEach(e -> byContract.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue()));
            for (String contract : chunk) {
              // Compara sem caixa: no EVM a chave da resposta vem em minúsculas.
              ProviderQuote quote = read(byContract.get(contract.toLowerCase(Locale.ROOT)));
              if (quote != null) {
                quotes.put(new AssetRef(network, contract), quote);
              }
            }
          }
        });
    return quotes;
  }

  private JsonNode get(String uri, Object... variables) {
    String body = client.get().uri(uri, variables).retrieve().body(String.class);
    try {
      return JSON.readTree(body == null ? "{}" : body);
    } catch (JacksonException e) {
      throw new IllegalStateException("resposta da coingecko fora do formato", e);
    }
  }

  // Nulo para preço que não é número positivo ou sem a data: melhor sem cotação que uma errada.
  private static ProviderQuote read(JsonNode item) {
    if (item == null || !item.isObject()) {
      return null;
    }
    JsonNode usd = item.get("usd");
    JsonNode updated = item.get("last_updated_at");
    if (usd == null || !usd.isNumber() || updated == null || !updated.isIntegralNumber()) {
      log.warn("cotação da coingecko ignorada: formato inesperado");
      return null;
    }
    BigDecimal price = usd.decimalValue();
    if (price.signum() <= 0) {
      log.warn("cotação da coingecko ignorada: preço não positivo");
      return null;
    }
    return new ProviderQuote(price, Instant.ofEpochSecond(updated.longValue()));
  }
}
