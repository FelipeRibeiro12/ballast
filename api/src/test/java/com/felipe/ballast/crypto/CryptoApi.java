package com.felipe.ballast.crypto;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Rotas de cripto como o cliente faria. Público porque o teste de patrimônio também precisa.
public final class CryptoApi {

  static final JsonMapper JSON = JsonMapper.builder().build();

  // Um usuário com sessão, uma conta corrente e uma conta cripto.
  public record Actor(
      TestUsers.Registered user, Cookie session, String checkingId, String cryptoId) {}

  private final MockMvc mvc;

  public CryptoApi(MockMvc mvc) {
    this.mvc = mvc;
  }

  public Actor newActor() throws Exception {
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered user = users.register();
    Cookie session = users.session(user);
    String checking = createAccountOk(session, "Corrente", "corrente");
    String crypto = createAccountOk(session, "Exchange", "cripto");
    return new Actor(user, session, checking, crypto);
  }

  public String createAccountOk(Cookie session, String name, String type) throws Exception {
    return idOf(send(session, post("/contas"), Map.of("nome", name, "tipo", type)));
  }

  // Ativo nativo de bitcoin com 8 casas.
  public static Map<String, Object> bitcoin() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rede", "bitcoin");
    body.put("simbolo", "BTC");
    body.put("nome", "Bitcoin");
    body.put("decimais", 8);
    return body;
  }

  public MockHttpServletResponse createAsset(Cookie session, Object body) throws Exception {
    return send(session, post("/ativos-cripto"), body);
  }

  public String createAssetOk(Cookie session, Object body) throws Exception {
    return idOf(createAsset(session, body));
  }

  public MockHttpServletResponse listAssets(Cookie session) throws Exception {
    return send(session, get("/ativos-cripto"), null);
  }

  public MockHttpServletResponse quote(Cookie session, String assetId, Object body)
      throws Exception {
    return send(session, post("/ativos-cripto/" + assetId + "/cotacoes"), body);
  }

  public void quoteOk(Cookie session, String assetId, String price, String moment)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("preco", price);
    body.put("momento", moment);
    expect(quote(session, assetId, body), 201);
  }

  public static Map<String, Object> movement(
      String accountId, String assetId, String type, String quantity, String date, long cents) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaId", accountId);
    body.put("ativoId", assetId);
    body.put("tipo", type);
    body.put("quantidade", quantity);
    body.put("data", date);
    body.put("custoCentavos", cents);
    return body;
  }

  public MockHttpServletResponse move(Cookie session, Object body) throws Exception {
    return send(session, post("/movimentos-cripto"), body);
  }

  public String moveOk(Cookie session, Object body) throws Exception {
    return idOf(move(session, body));
  }

  public MockHttpServletResponse listMovements(Cookie session, String query) throws Exception {
    return send(session, get("/movimentos-cripto" + query), null);
  }

  public MockHttpServletResponse removeMovement(Cookie session, String id) throws Exception {
    return send(session, delete("/movimentos-cripto/" + id), null);
  }

  public MockHttpServletResponse positions(Cookie session, String query) throws Exception {
    return send(session, get("/posicoes-cripto" + query), null);
  }

  public MockHttpServletResponse netWorth(Cookie session, String query) throws Exception {
    return send(session, get("/patrimonio" + query), null);
  }

  // body nulo manda a requisição sem corpo; String vai como está, o resto vira JSON.
  public MockHttpServletResponse send(
      Cookie session, MockHttpServletRequestBuilder request, Object body) throws Exception {
    if (body != null) {
      request
          .contentType(MediaType.APPLICATION_JSON)
          .content(body instanceof String s ? s : JSON.writeValueAsString(body));
    }
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private static String idOf(MockHttpServletResponse response) throws Exception {
    return expect(response, 201).get("id").asString();
  }

  static JsonNode expect(MockHttpServletResponse response, int status) throws Exception {
    if (response.getStatus() != status) {
      throw new IllegalStateException(
          "chamada de teste falhou: " + response.getStatus() + " " + text(response));
    }
    return text(response).isEmpty() ? null : body(response);
  }

  public static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(text(response));
  }

  public static String text(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString(StandardCharsets.UTF_8);
  }

  public static String randomId() {
    return UUID.randomUUID().toString();
  }
}
