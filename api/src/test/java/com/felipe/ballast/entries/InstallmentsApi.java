package com.felipe.ballast.entries;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Chamadas às rotas de compra parcelada como o cliente faria. O cartão de teste fecha no dia 15 e
// vence no dia 25: a compra de 2026-03-10 tem a primeira parcela em 2026-03-25.
final class InstallmentsApi {

  record Shopper(EntriesApi.Actor actor, String cardId) {
    Cookie session() {
      return actor.session();
    }
  }

  private final MockMvc mvc;
  private final EntriesApi entries;

  InstallmentsApi(MockMvc mvc) {
    this.mvc = mvc;
    this.entries = new EntriesApi(mvc);
  }

  Shopper newShopper() throws Exception {
    EntriesApi.Actor actor = entries.newActor();
    String card =
        entries.newCard(actor.session(), "Cartão " + UUID.randomUUID(), actor.accountId(), 15, 25);
    return new Shopper(actor, card);
  }

  EntriesApi entries() {
    return entries;
  }

  // Corpo válido: R$ 1.000,00 em 3x no cartão do comprador.
  static Map<String, Object> purchaseBody(Shopper shopper) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("titulo", "Notebook");
    body.put("valorTotalCentavos", 100_000);
    body.put("parcelas", 3);
    body.put("categoriaId", shopper.actor().expenseCategoryId());
    body.put("contaId", shopper.cardId());
    body.put("data", "2026-03-10");
    return body;
  }

  MockHttpServletResponse create(Cookie session, Object body) throws Exception {
    var request =
        post("/compras-parceladas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(EntriesApi.JSON.writeValueAsString(body));
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  JsonNode createOk(Cookie session, Object body) throws Exception {
    MockHttpServletResponse response = create(session, body);
    if (response.getStatus() != 201) {
      throw new IllegalStateException(
          "compra de teste falhou: "
              + response.getStatus()
              + " "
              + response.getContentAsString(StandardCharsets.UTF_8));
    }
    return EntriesApi.body(response);
  }

  MockHttpServletResponse find(Cookie session, String id) throws Exception {
    var request = get("/compras-parceladas/" + id);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse patch(Cookie session, String id, Object body) throws Exception {
    var request =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                "/compras-parceladas/" + id)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body instanceof String s ? s : EntriesApi.JSON.writeValueAsString(body));
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse remove(Cookie session, String id) throws Exception {
    var request = delete("/compras-parceladas/" + id);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  static String lancamentoId(JsonNode purchase, int index) {
    return purchase.get("itens").get(index).get("lancamentoId").asString();
  }
}
