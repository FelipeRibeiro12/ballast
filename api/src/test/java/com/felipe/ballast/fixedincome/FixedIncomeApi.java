package com.felipe.ballast.fixedincome;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.crypto.CryptoApi;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.mock.web.MockHttpServletResponse;

// Rotas de renda fixa como o cliente faria, por cima do CryptoApi (sessão e envio).
final class FixedIncomeApi {

  private final CryptoApi api;

  FixedIncomeApi(CryptoApi api) {
    this.api = api;
  }

  static Map<String, Object> title(
      String accountId, String type, String indexer, String rate, long cents, String date) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaId", accountId);
    body.put("tipo", type);
    body.put("nome", type.toUpperCase() + " " + indexer);
    body.put("valorAplicadoCentavos", cents);
    body.put("dataAplicacao", date);
    body.put("indexador", indexer);
    body.put("taxa", rate);
    return body;
  }

  MockHttpServletResponse create(Cookie session, Object body) throws Exception {
    return api.send(session, post("/titulos-renda-fixa"), body);
  }

  String createOk(Cookie session, Object body) throws Exception {
    return CryptoApi.expect(create(session, body), 201).get("id").asString();
  }

  MockHttpServletResponse update(Cookie session, String id, Object body) throws Exception {
    return api.send(session, put("/titulos-renda-fixa/" + id), body);
  }

  MockHttpServletResponse remove(Cookie session, String id) throws Exception {
    return api.send(session, delete("/titulos-renda-fixa/" + id), null);
  }

  MockHttpServletResponse list(Cookie session, String query) throws Exception {
    return api.send(session, get("/titulos-renda-fixa" + query), null);
  }

  MockHttpServletResponse mark(Cookie session, String id, String date, long cents)
      throws Exception {
    return api.send(
        session,
        post("/titulos-renda-fixa/" + id + "/marcacoes"),
        Map.of("data", date, "valorCentavos", cents));
  }

  MockHttpServletResponse redeem(Cookie session, String id, String date, long cents)
      throws Exception {
    return api.send(
        session,
        post("/titulos-renda-fixa/" + id + "/resgate"),
        Map.of("data", date, "valorCentavos", cents));
  }

  MockHttpServletResponse undoRedemption(Cookie session, String id) throws Exception {
    return api.send(session, delete("/titulos-renda-fixa/" + id + "/resgate"), null);
  }

  MockHttpServletResponse indices(Cookie session) throws Exception {
    return api.send(session, get("/indices"), null);
  }
}
