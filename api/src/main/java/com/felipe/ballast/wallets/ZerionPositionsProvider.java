package com.felipe.ballast.wallets;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// API v1 da Zerion (ADR-081). O endereço vai no caminho da URL, que é como a Zerion pede, então
// nenhuma exceção do RestClient sai daqui: a mensagem dela traz a URL. O log leva só o status.
@Component
class ZerionPositionsProvider implements PositionsProvider {

  private static final Logger log = LoggerFactory.getLogger(ZerionPositionsProvider.class);

  private static final Duration TIMEOUT = Duration.ofSeconds(8);
  static final int MAX_POSITIONS = 200;
  private static final int MAX_TEXT = 100;
  private static final BigDecimal MAX_USD = new BigDecimal("10000000000000");
  private static final BigDecimal MAX_QUANTITY = new BigDecimal("99999999999999999999");

  private static final Map<WalletNetwork, String> CHAIN =
      Map.of(
          WalletNetwork.ETHEREUM, "ethereum",
          WalletNetwork.BASE, "base",
          WalletNetwork.ARBITRUM, "arbitrum",
          WalletNetwork.POLYGON, "polygon",
          WalletNetwork.AVALANCHE, "avalanche",
          WalletNetwork.ROBINHOOD, "robinhood",
          WalletNetwork.SOLANA, "solana");

  // O valor chega como número JSON: nunca double.
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private final boolean configured;
  private final RestClient client;

  @Autowired
  ZerionPositionsProvider(ZerionProperties properties) {
    this(properties.hasKey(), clientOf(properties));
  }

  // O teste entrega um cliente ligado ao servidor simulado do Spring.
  ZerionPositionsProvider(boolean configured, RestClient client) {
    this.configured = configured;
    this.client = client;
  }

  private static RestClient clientOf(ZerionProperties properties) {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    factory.setReadTimeout(TIMEOUT);
    RestClient.Builder builder = RestClient.builder().baseUrl(properties.url());
    builder.requestFactory(factory);
    if (properties.hasKey()) {
      // Basic com a chave no usuário e a senha vazia, como a Zerion documenta.
      String token =
          Base64.getEncoder()
              .encodeToString((properties.apiKey() + ":").getBytes(StandardCharsets.UTF_8));
      builder.defaultHeader("Authorization", "Basic " + token);
    }
    return builder.build();
  }

  @Override
  public boolean isConfigured() {
    return configured;
  }

  @Override
  public Optional<List<Position>> read(WalletNetwork network, String address) {
    String chain = CHAIN.get(network);
    if (!configured || chain == null) {
      return Optional.of(List.of());
    }
    Optional<JsonNode> defi =
        get(
            "/wallets/{address}/positions/?currency=usd&filter[positions]=only_complex"
                + "&filter[chain_ids]={chain}&filter[trash]=only_non_trash",
            address,
            chain);
    Optional<JsonNode> nfts =
        get(
            "/wallets/{address}/nft-positions/?currency=usd&filter[chain_ids]={chain}"
                + "&sort=-floor_price&page[size]=100",
            address,
            chain);
    // Uma das duas falhar não pode apagar a outra: sem as duas, mantém tudo como estava.
    if (defi.isEmpty() || nfts.isEmpty()) {
      return Optional.empty();
    }
    List<Position> all = new ArrayList<>(parseDefi(defi.get()));
    all.addAll(parseNfts(nfts.get()));
    return Optional.of(all);
  }

  // Vazio quando a Zerion não deu uma resposta usável. 400 é rede ou endereço que ela não cobre:
  // vira "sem posição". 202 é dado ainda sendo preparado: tenta de novo na próxima leitura.
  private Optional<JsonNode> get(String uri, String address, String chain) {
    try {
      var response = client.get().uri(uri, address, chain).retrieve().toEntity(String.class);
      if (response.getStatusCode().value() == 202) {
        return Optional.empty();
      }
      String body = response.getBody();
      JsonNode root = JSON.readTree(body == null ? "" : body);
      return root.path("data").isArray() ? Optional.of(root) : Optional.empty();
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      log.warn("zerion respondeu erro: status={}", status);
      return status == 400 ? Optional.of(JSON.readTree("{\"data\":[]}")) : Optional.empty();
    } catch (JacksonException e) {
      log.warn("zerion respondeu fora do formato");
      return Optional.empty();
    } catch (RuntimeException e) {
      log.warn("zerion sem resposta: tipo={}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  static List<Position> parseDefi(JsonNode root) {
    Map<String, Group> groups = new LinkedHashMap<>();
    int anonymous = 0;
    for (JsonNode item : root.path("data")) {
      JsonNode attrs = item.path("attributes");
      JsonNode flags = attrs.path("flags");
      if (flags.path("is_trash").asBoolean(false) || !flags.path("displayable").asBoolean(true)) {
        continue;
      }
      String module = text(attrs.get("protocol_module"));
      String type = text(attrs.get("position_type"));
      String symbol = clean(text(attrs.path("fungible_info").get("symbol")));
      String name = clean(text(attrs.get("name")));
      boolean pool = "liquidity_pool".equals(module);
      String groupId = text(attrs.get("group_id"));
      String key = pool && groupId != null ? groupId : "p" + (anonymous++);
      BigDecimal value = number(attrs.get("value"));
      if ("loan".equals(type) && value != null) {
        value = value.negate();
      }
      Group group =
          groups.computeIfAbsent(
              key, k -> new Group(categoryOf(module, type), clean(text(attrs.get("protocol")))));
      BigDecimal change = number(attrs.path("changes").get("absolute_1d"));
      // Empréstimo tem valor negativo no app, então a variação troca de sinal junto: dívida que
      // sobe é patrimônio que cai.
      if ("loan".equals(type) && change != null) {
        change = change.negate();
      }
      group.symbols.add(symbol != null ? symbol : name);
      group.detail = group.detail == null ? name : group.detail;
      group.quantity = pool ? null : number(attrs.path("quantity").get("numeric"));
      if (value != null) {
        group.value = group.value == null ? value : group.value.add(value);
      }
      // Soma das pernas do pool; perna sem variação não zera a das outras.
      if (change != null) {
        group.change = group.change == null ? change : group.change.add(change);
      }
    }
    List<Position> out = new ArrayList<>();
    for (Group group : groups.values()) {
      group.symbols.remove(null);
      if (group.symbols.isEmpty()) {
        continue;
      }
      String label = clean(String.join(" / ", group.symbols));
      out.add(
          new Position(
              "defi",
              group.category,
              group.protocol,
              label,
              label.equals(group.detail) ? null : group.detail,
              fitQuantity(group.quantity),
              toCents(group.value),
              toCents(group.change)));
      if (out.size() == MAX_POSITIONS) {
        break;
      }
    }
    return out;
  }

  static List<Position> parseNfts(JsonNode root) {
    List<Position> out = new ArrayList<>();
    for (JsonNode item : root.path("data")) {
      JsonNode attrs = item.path("attributes");
      JsonNode info = attrs.path("nft_info");
      if (info.path("flags").path("is_spam").asBoolean(false)) {
        continue;
      }
      String tokenId = text(info.get("token_id"));
      String name = clean(text(info.get("name")));
      if (name == null) {
        name = tokenId == null ? null : clean("#" + tokenId);
      }
      if (name == null) {
        continue;
      }
      BigDecimal amount = number(attrs.get("amount"));
      BigDecimal price = number(attrs.get("price"));
      BigDecimal value =
          price == null ? null : price.multiply(amount == null ? BigDecimal.ONE : amount);
      out.add(
          new Position(
              "nft",
              "nft",
              clean(text(attrs.path("collection_info").get("name"))),
              name,
              clean(text(info.get("interface"))),
              fitQuantity(amount),
              toCents(value),
              null));
      if (out.size() == MAX_POSITIONS) {
        break;
      }
    }
    return out;
  }

  private static final class Group {
    final String category;
    final String protocol;
    final Set<String> symbols = new LinkedHashSet<>();
    String detail;
    BigDecimal quantity;
    BigDecimal value;
    BigDecimal change;

    Group(String category, String protocol) {
      this.category = category;
      this.protocol = protocol;
    }
  }

  // Os nomes de categoria são os do CHECK da V44.
  static String categoryOf(String module, String type) {
    if ("loan".equals(type)) {
      return "emprestimo";
    }
    if (module != null) {
      switch (module) {
        case "liquidity_pool":
          return "liquidez";
        case "staked", "nft_staked":
          return "stake";
        case "lending", "deposit":
          return "deposito";
        case "yield", "farming", "leveraged_farming":
          return "rendimento";
        case "locked", "vesting":
          return "bloqueado";
        case "rewards":
          return "recompensa";
        default:
          break;
      }
    }
    if (type == null) {
      return "outro";
    }
    return switch (type) {
      case "staked" -> "stake";
      case "deposit" -> "deposito";
      case "locked" -> "bloqueado";
      case "reward" -> "recompensa";
      default -> "outro";
    };
  }

  private static String text(JsonNode node) {
    return node != null && node.isString() ? node.asString() : null;
  }

  // Número JSON ou texto numérico; nulo para o resto.
  private static BigDecimal number(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      if (node.isNumber()) {
        return node.decimalValue();
      }
      return node.isString() ? new BigDecimal(node.asString()) : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // Centavos de dólar com HALF_EVEN, uma vez, na saída (ADR-005). Nulo quando não cabe.
  private static Long toCents(BigDecimal usd) {
    if (usd == null || usd.abs().compareTo(MAX_USD) > 0) {
      return null;
    }
    return usd.movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
  }

  // numeric(38,18): 20 dígitos inteiros e 18 casas.
  private static BigDecimal fitQuantity(BigDecimal quantity) {
    if (quantity == null || quantity.abs().compareTo(MAX_QUANTITY) > 0) {
      return null;
    }
    return quantity.setScale(Math.min(Math.max(quantity.scale(), 0), 18), RoundingMode.HALF_EVEN);
  }

  // Texto de terceiro: sem caracteres de controle, espaço repetido vira um e corta em 100.
  static String clean(String raw) {
    if (raw == null) {
      return null;
    }
    String cleaned =
        raw.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "").replaceAll("\\s+", " ").strip();
    int[] points = cleaned.codePoints().limit(MAX_TEXT).toArray();
    String cut = new String(points, 0, points.length).strip();
    return cut.isEmpty() ? null : cut;
  }
}
