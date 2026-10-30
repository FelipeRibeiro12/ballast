package com.felipe.ballast.wallets;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// JSON-RPC da Alchemy (ADR-071). A chave faz parte da URL, então nenhuma exceção do RestClient
// sai daqui: a mensagem dela traz a URL. O endereço vai só no corpo do POST. O log tem a classe e
// o status, nunca URL nem endereço.
@Component
class AlchemyWalletIndexer implements WalletIndexer {

  private static final Logger log = LoggerFactory.getLogger(AlchemyWalletIndexer.class);

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  static final int MAX_TOKENS = 100;

  private static final Pattern HEX = Pattern.compile("0x[0-9a-fA-F]{0,64}");
  private static final Pattern EVM_CONTRACT = Pattern.compile("0x[0-9a-fA-F]{40}");
  private static final String TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
  private static final String TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb";

  // Quantidade vem em hexadecimal ou texto, mas o saldo do Solana e os preços chegam como número
  // em alguns campos: nunca double.
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private final String apiKey;
  private final Map<WalletNetwork, RestClient> clients;

  @Autowired
  AlchemyWalletIndexer(WalletsProperties properties) {
    this(properties.apiKey(), clientsOf(properties));
  }

  // O teste entrega clientes ligados ao servidor simulado do Spring.
  AlchemyWalletIndexer(String apiKey, Map<WalletNetwork, RestClient> clients) {
    this.apiKey = apiKey;
    this.clients = clients;
  }

  private static Map<WalletNetwork, RestClient> clientsOf(WalletsProperties properties) {
    Map<WalletNetwork, RestClient> clients = new EnumMap<>(WalletNetwork.class);
    for (WalletNetwork network : WalletNetwork.values()) {
      JdkClientHttpRequestFactory factory =
          new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
      factory.setReadTimeout(TIMEOUT);
      clients.put(
          network,
          RestClient.builder().baseUrl(properties.urlOf(network)).requestFactory(factory).build());
    }
    return clients;
  }

  @Override
  public boolean isConfigured() {
    return apiKey != null && !apiKey.isBlank();
  }

  @Override
  public Reading read(WalletNetwork network, String address) {
    if (!isConfigured()) {
      throw new IndexerUnavailableException(IndexerUnavailableException.Reason.NO_KEY);
    }
    return network.isEvm() ? readEvm(network, address) : readSolana(address);
  }

  private Reading readEvm(WalletNetwork network, String address) {
    List<Holding> holdings = new ArrayList<>();
    JsonNode balance = call(network, "eth_getBalance", List.of(address, "latest"));
    holdings.add(nativeHolding(network, wei(balance)));

    JsonNode tokens = call(network, "alchemy_getTokenBalances", List.of(address, "erc20"));
    JsonNode list = tokens.get("tokenBalances");
    if (list == null || !list.isArray()) {
      throw invalid();
    }
    boolean partial = tokens.hasNonNull("pageKey");
    Map<String, BigInteger> raw = new LinkedHashMap<>();
    for (JsonNode item : list) {
      JsonNode contract = item.get("contractAddress");
      if (item.hasNonNull("error")
          || contract == null
          || !contract.isString()
          || !EVM_CONTRACT.matcher(contract.asString()).matches()
          || !item.hasNonNull("tokenBalance")) {
        partial = true;
        continue;
      }
      BigInteger amount = wei(item.get("tokenBalance"));
      if (amount.signum() == 0) {
        continue;
      }
      if (raw.size() == MAX_TOKENS) {
        partial = true;
        break;
      }
      raw.merge(contract.asString().toLowerCase(Locale.ROOT), amount, BigInteger::add);
    }
    if (!raw.isEmpty()) {
      partial |= addTokens(network, raw, holdings);
    }
    return new Reading(holdings, partial);
  }

  // Um pedido em lote para os metadados de todos os tokens. Devolve se algum ficou de fora.
  private boolean addTokens(
      WalletNetwork network, Map<String, BigInteger> raw, List<Holding> holdings) {
    List<String> contracts = List.copyOf(raw.keySet());
    List<Map<String, Object>> batch = new ArrayList<>();
    for (int i = 0; i < contracts.size(); i++) {
      batch.add(request(i, "alchemy_getTokenMetadata", List.of(contracts.get(i))));
    }
    JsonNode responses = post(network, batch);
    if (!responses.isArray()) {
      throw invalid();
    }
    Map<Integer, JsonNode> byId = new LinkedHashMap<>();
    for (JsonNode response : responses) {
      JsonNode id = response.get("id");
      if (id != null && id.isIntegralNumber() && response.hasNonNull("result")) {
        byId.put(id.intValue(), response.get("result"));
      }
    }
    boolean skipped = false;
    for (int i = 0; i < contracts.size(); i++) {
      JsonNode metadata = byId.get(i);
      JsonNode decimals = metadata == null ? null : metadata.get("decimals");
      if (decimals == null
          || !decimals.isIntegralNumber()
          || decimals.intValue() < 0
          || decimals.intValue() > 18) {
        skipped = true;
        continue;
      }
      JsonNode symbol = metadata.get("symbol");
      holdings.add(
          new Holding(
              contracts.get(i),
              symbol != null && symbol.isString() ? symbol.asString() : null,
              decimals.intValue(),
              new BigDecimal(raw.get(contracts.get(i)), decimals.intValue())));
    }
    return skipped;
  }

  private Reading readSolana(String address) {
    WalletNetwork network = WalletNetwork.SOLANA;
    List<Holding> holdings = new ArrayList<>();
    JsonNode balance = call(network, "getBalance", List.of(address));
    JsonNode lamports = balance.get("value");
    if (lamports == null || !lamports.isIntegralNumber() || lamports.longValue() < 0) {
      throw invalid();
    }
    holdings.add(nativeHolding(network, BigInteger.valueOf(lamports.longValue())));

    boolean partial = false;
    Map<String, BigDecimal> byMint = new LinkedHashMap<>();
    Map<String, Integer> decimalsByMint = new LinkedHashMap<>();
    for (String program : List.of(TOKEN_PROGRAM, TOKEN_2022_PROGRAM)) {
      JsonNode accounts =
          call(
              network,
              "getTokenAccountsByOwner",
              List.of(address, Map.of("programId", program), Map.of("encoding", "jsonParsed")));
      JsonNode value = accounts.get("value");
      if (value == null || !value.isArray()) {
        throw invalid();
      }
      for (JsonNode account : value) {
        JsonNode info = account.path("account").path("data").path("parsed").path("info");
        JsonNode mint = info.get("mint");
        JsonNode amount = info.path("tokenAmount");
        JsonNode ui = amount.get("uiAmountString");
        JsonNode decimals = amount.get("decimals");
        if (mint == null
            || !mint.isString()
            || WalletAddress.normalize(network, mint.asString()).isEmpty()
            || ui == null
            || !ui.isString()
            || decimals == null
            || !decimals.isIntegralNumber()
            || decimals.intValue() < 0
            || decimals.intValue() > 18) {
          partial = true;
          continue;
        }
        BigDecimal quantity;
        try {
          quantity = new BigDecimal(ui.asString());
        } catch (NumberFormatException e) {
          partial = true;
          continue;
        }
        if (quantity.signum() <= 0) {
          continue;
        }
        if (!byMint.containsKey(mint.asString()) && byMint.size() == MAX_TOKENS) {
          partial = true;
          continue;
        }
        byMint.merge(mint.asString(), quantity, BigDecimal::add);
        decimalsByMint.put(mint.asString(), decimals.intValue());
      }
    }
    // O RPC do Solana não traz metadado: o símbolo fica nulo e o serviço usa o começo do mint.
    byMint.forEach(
        (mint, quantity) ->
            holdings.add(new Holding(mint, null, decimalsByMint.get(mint), quantity)));
    return new Reading(holdings, partial);
  }

  private static Holding nativeHolding(WalletNetwork network, BigInteger smallestUnit) {
    return new Holding(
        null,
        network.nativeSymbol(),
        network.nativeDecimals(),
        new BigDecimal(smallestUnit, network.nativeDecimals()));
  }

  private static BigInteger wei(JsonNode node) {
    if (node == null || !node.isString() || !HEX.matcher(node.asString()).matches()) {
      throw invalid();
    }
    String digits = node.asString().substring(2);
    return digits.isEmpty() ? BigInteger.ZERO : new BigInteger(digits, 16);
  }

  private JsonNode call(WalletNetwork network, String method, List<?> params) {
    JsonNode response = post(network, request(1, method, params));
    if (response.hasNonNull("error")) {
      log.warn("indexador devolveu erro de RPC: metodo={}", method);
      throw new IndexerUnavailableException(IndexerUnavailableException.Reason.UNAVAILABLE);
    }
    JsonNode result = response.get("result");
    if (result == null || result.isNull()) {
      throw invalid();
    }
    return result;
  }

  private static Map<String, Object> request(int id, String method, List<?> params) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("jsonrpc", "2.0");
    body.put("id", id);
    body.put("method", method);
    body.put("params", params);
    return body;
  }

  // Toda falha sai como IndexerUnavailableException sem causa. Capturar RuntimeException inteiro é
  // de propósito: qualquer subclasse do RestClient pode trazer a URL na mensagem.
  private JsonNode post(WalletNetwork network, Object body) {
    String response;
    try {
      response =
          clients
              .get(network)
              .post()
              .uri("/v2/{key}", apiKey)
              .contentType(MediaType.APPLICATION_JSON)
              .body(JSON.writeValueAsString(body))
              .retrieve()
              .body(String.class);
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      log.warn("indexador respondeu erro: status={}", status);
      throw new IndexerUnavailableException(reasonOf(status));
    } catch (RuntimeException e) {
      log.warn("indexador sem resposta: tipo={}", e.getClass().getSimpleName());
      throw new IndexerUnavailableException(IndexerUnavailableException.Reason.UNAVAILABLE);
    }
    try {
      return JSON.readTree(response == null ? "" : response);
    } catch (JacksonException e) {
      throw invalid();
    }
  }

  private static IndexerUnavailableException.Reason reasonOf(int status) {
    return switch (status) {
      case 401, 403 -> IndexerUnavailableException.Reason.REJECTED;
      case 429 -> IndexerUnavailableException.Reason.RATE_LIMITED;
      default -> IndexerUnavailableException.Reason.UNAVAILABLE;
    };
  }

  private static IndexerUnavailableException invalid() {
    return new IndexerUnavailableException(IndexerUnavailableException.Reason.INVALID_RESPONSE);
  }
}
