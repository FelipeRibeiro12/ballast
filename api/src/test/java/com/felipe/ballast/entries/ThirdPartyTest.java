package com.felipe.ballast.entries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class ThirdPartyTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
  private EntriesApi api;
  private EntriesApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    actor = api.newActor();
  }

  private MockHttpServletResponse send(
      Cookie session, MockHttpServletRequestBuilder request, Object body) throws Exception {
    request.cookie(session);
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private JsonNode json(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private String newThirdParty(Cookie session, String name) throws Exception {
    MockHttpServletResponse response = send(session, post("/terceiros"), Map.of("nome", name));
    assertThat(response.getStatus()).isEqualTo(201);
    return json(response).get("id").asString();
  }

  private int link(Cookie session, String entryId, String thirdPartyId) throws Exception {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("terceiroId", thirdPartyId);
    return send(session, put("/lancamentos/" + entryId + "/terceiro"), body).getStatus();
  }

  private JsonNode thirdParty(Cookie session, String id) throws Exception {
    for (JsonNode item : json(send(session, get("/terceiros"), null))) {
      if (item.get("id").asString().equals(id)) {
        return item;
      }
    }
    throw new IllegalStateException(id);
  }

  @Test
  void whatIsOwedIsPurchasesMinusReimbursements() throws Exception {
    String friend = newThirdParty(actor.session(), "Maria");
    JsonNode purchase = api.createOk(actor.session(), EntriesApi.expenseBody(actor));
    assertThat(link(actor.session(), purchase.get("id").asString(), friend)).isEqualTo(204);

    JsonNode afterPurchase = thirdParty(actor.session(), friend);
    assertThat(afterPurchase.get("compradoCentavos").asLong()).isEqualTo(12_345);
    assertThat(afterPurchase.get("devidoCentavos").asLong()).isEqualTo(12_345);

    Map<String, Object> reimbursement = new java.util.LinkedHashMap<>(EntriesApi.incomeBody(actor));
    reimbursement.put("valorCentavos", 2_000);
    JsonNode received = api.createOk(actor.session(), reimbursement);
    assertThat(link(actor.session(), received.get("id").asString(), friend)).isEqualTo(204);

    JsonNode afterRepayment = thirdParty(actor.session(), friend);
    assertThat(afterRepayment.get("recebidoCentavos").asLong()).isEqualTo(2_000);
    assertThat(afterRepayment.get("devidoCentavos").asLong()).isEqualTo(10_345);
    assertThat(
            EntriesApi.body(api.find(actor.session(), purchase.get("id").asString()))
                .get("terceiroId")
                .asString())
        .isEqualTo(friend);
  }

  @Test
  void whatIsOwedThisMonthIgnoresFutureInstallmentsAndUsesAPaymentInFullToCoverThem()
      throws Exception {
    String friend = newThirdParty(actor.session(), "Bia");
    JsonNode current = api.createOk(actor.session(), EntriesApi.expenseBody(actor));
    link(actor.session(), current.get("id").asString(), friend);
    Map<String, Object> later = new java.util.LinkedHashMap<>(EntriesApi.expenseBody(actor));
    later.put("data", "2099-01-10");
    JsonNode future = api.createOk(actor.session(), later);
    link(actor.session(), future.get("id").asString(), friend);

    JsonNode before = thirdParty(actor.session(), friend);
    assertThat(before.get("devidoCentavos").asLong()).isEqualTo(24_690);
    assertThat(before.get("devidoNoMesCentavos").asLong()).isEqualTo(12_345);

    // Pagou o total de uma vez: o mês zera e a sobra do valor cobre o mês que vem.
    Map<String, Object> everything = new java.util.LinkedHashMap<>(EntriesApi.incomeBody(actor));
    everything.put("valorCentavos", 24_690);
    JsonNode received = api.createOk(actor.session(), everything);
    link(actor.session(), received.get("id").asString(), friend);

    JsonNode after = thirdParty(actor.session(), friend);
    assertThat(after.get("devidoCentavos").asLong()).isZero();
    assertThat(after.get("devidoNoMesCentavos").asLong()).isZero();
  }

  @Test
  void aLinkedPurchaseLeavesTheMonthlyReport() throws Exception {
    String friend = newThirdParty(actor.session(), "João");
    JsonNode purchase = api.createOk(actor.session(), EntriesApi.expenseBody(actor));
    JsonNode before = json(send(actor.session(), get("/relatorios?mes=2026-03"), null));
    assertThat(before.get("totalDespesasCentavos").asLong()).isEqualTo(12_345);

    link(actor.session(), purchase.get("id").asString(), friend);

    JsonNode after = json(send(actor.session(), get("/relatorios?mes=2026-03"), null));
    assertThat(after.get("totalDespesasCentavos").asLong()).isZero();
  }

  @Test
  void unlinkingBringsThePurchaseBack() throws Exception {
    String friend = newThirdParty(actor.session(), "Ana");
    JsonNode purchase = api.createOk(actor.session(), EntriesApi.expenseBody(actor));
    link(actor.session(), purchase.get("id").asString(), friend);

    assertThat(link(actor.session(), purchase.get("id").asString(), null)).isEqualTo(204);

    assertThat(thirdParty(actor.session(), friend).get("compradoCentavos").asLong()).isZero();
  }

  @Test
  void aThirdPartyWithEntriesIsArchivedNotDeleted() throws Exception {
    String friend = newThirdParty(actor.session(), "Rui");
    JsonNode purchase = api.createOk(actor.session(), EntriesApi.expenseBody(actor));
    link(actor.session(), purchase.get("id").asString(), friend);

    assertThat(send(actor.session(), delete("/terceiros/" + friend), null).getStatus())
        .isEqualTo(409);
    assertThat(
            send(
                    actor.session(),
                    put("/terceiros/" + friend),
                    Map.of("nome", "Rui", "arquivado", true))
                .getStatus())
        .isEqualTo(200);
    JsonNode another = api.createOk(actor.session(), EntriesApi.expenseBody(actor));
    assertThat(link(actor.session(), another.get("id").asString(), friend)).isEqualTo(400);
  }

  @Test
  void anotherUserCannotSeeOrUseTheThirdParty() throws Exception {
    String friend = newThirdParty(actor.session(), "Eva");
    EntriesApi.Actor other = api.newActor();
    JsonNode otherPurchase = api.createOk(other.session(), EntriesApi.expenseBody(other));

    assertThat(link(other.session(), otherPurchase.get("id").asString(), friend)).isEqualTo(404);
    assertThat(
            send(other.session(), get("/terceiros/" + friend + "/lancamentos"), null).getStatus())
        .isEqualTo(404);
    assertThat(send(other.session(), put("/terceiros/" + friend), Map.of("nome", "X")).getStatus())
        .isEqualTo(404);
    assertThat(send(other.session(), delete("/terceiros/" + friend), null).getStatus())
        .isEqualTo(404);
    assertThat(json(send(other.session(), get("/terceiros"), null))).isEmpty();
  }

  @Test
  void theEntriesOfAThirdPartyComeCappedAtTwoHundred() throws Exception {
    String friend = newThirdParty(actor.session(), "Cap");
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,
                                    data_competencia, data_caixa, status)
            SELECT gen_random_uuid(), CAST(:u AS uuid), 'despesa', 'x', CAST(:c AS uuid),
                   CAST(:a AS uuid), 1, DATE '2026-03-10', DATE '2026-03-10', 'realizado'
            FROM generate_series(1, 205)
            """)
        .param("u", actor.user().id())
        .param("c", actor.expenseCategoryId())
        .param("a", actor.accountId())
        .update();
    jdbc.sql(
            """
            INSERT INTO terceiro_lancamento (lancamento_id, usuario_id, terceiro_id)
            SELECT id, usuario_id, CAST(:t AS uuid) FROM lancamento
            WHERE usuario_id = CAST(:u AS uuid) AND titulo = 'x'
            """)
        .param("t", friend)
        .param("u", actor.user().id())
        .update();

    JsonNode entries =
        json(send(actor.session(), get("/terceiros/" + friend + "/lancamentos"), null));

    assertThat(entries).hasSize(200);
  }

  @Test
  void refusesADuplicateName() throws Exception {
    newThirdParty(actor.session(), "Bia");

    assertThat(send(actor.session(), post("/terceiros"), Map.of("nome", "bia")).getStatus())
        .isEqualTo(409);
  }
}
