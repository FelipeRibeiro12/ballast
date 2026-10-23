package com.felipe.ballast.fixedincome;

import static com.felipe.ballast.fixedincome.FixedIncomeApi.title;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.crypto.CryptoApi;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class FixedIncomeTitleTest {

  private static final String NOT_FOUND = "{\"erro\":\"não encontrado\"}";

  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  private CryptoApi base;
  private FixedIncomeApi api;
  private CryptoApi.Actor actor;
  private String broker;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2034-06-15T15:00:00Z"));
    base = new CryptoApi(mvc);
    api = new FixedIncomeApi(base);
    actor = base.newActor();
    broker = base.createAccountOk(actor.session(), "Corretora", "investimento");
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private Map<String, Object> lci() {
    return title(broker, "lci", "cdi", "95", 100_000, "2034-01-10");
  }

  private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
    Map<String, Object> copy = new LinkedHashMap<>(body);
    copy.put(key, value);
    return copy;
  }

  @Test
  void createsATitleAndAnswersItWithTheRateAsText() throws Exception {
    Map<String, Object> body = with(lci(), "vencimento", "2036-01-10");

    MockHttpServletResponse created = api.create(actor.session(), body);

    assertThat(created.getStatus()).isEqualTo(201);
    JsonNode json = CryptoApi.body(created);
    assertThat(json.get("contaId").asString()).isEqualTo(broker);
    assertThat(json.get("tipo").asString()).isEqualTo("lci");
    assertThat(json.get("nome").asString()).isEqualTo("LCI cdi");
    assertThat(json.get("valorAplicadoCentavos").asLong()).isEqualTo(100_000);
    assertThat(json.get("dataAplicacao").asString()).isEqualTo("2034-01-10");
    assertThat(json.get("vencimento").asString()).isEqualTo("2036-01-10");
    assertThat(json.get("indexador").asString()).isEqualTo("cdi");
    assertThat(json.get("taxa").asString()).isEqualTo("95");
    assertThat(json.get("resgate").isNull()).isTrue();
    assertThat(json.get("ultimaMarcacao").isNull()).isTrue();
  }

  @Test
  void refusesBadFields() throws Exception {
    List<Object> bad =
        List.of(
            with(lci(), "tipo", "tesouro"),
            with(lci(), "indexador", "selic"),
            with(lci(), "taxa", "0"),
            with(lci(), "taxa", "-1"),
            with(lci(), "taxa", 95),
            with(lci(), "taxa", "123456"),
            with(lci(), "taxa", "1.12345"),
            with(lci(), "nome", ""),
            with(lci(), "nome", "x".repeat(101)),
            with(lci(), "valorAplicadoCentavos", 0),
            with(lci(), "valorAplicadoCentavos", 10.5),
            with(lci(), "dataAplicacao", "2034-02-30"),
            with(lci(), "vencimento", "2034-01-10"),
            with(lci(), "usuarioId", actor.user().id().toString()));
    for (Object body : bad) {
      assertThat(api.create(actor.session(), body).getStatus()).as(body.toString()).isEqualTo(400);
    }
    MockHttpServletResponse future =
        api.create(actor.session(), with(lci(), "dataAplicacao", "2034-06-16"));
    assertThat(CryptoApi.text(future))
        .isEqualTo("{\"erro\":\"dataAplicacao não pode ser no futuro\"}");
    assertThat(CryptoApi.body(api.list(actor.session(), "")).get("titulos")).isEmpty();
  }

  @Test
  void refusesAnAccountThatIsNotAnInvestmentOneOrNotOwnedOrArchived() throws Exception {
    CryptoApi.Actor other = base.newActor();
    String theirs = base.createAccountOk(other.session(), "Deles", "investimento");
    for (String account : List.of(theirs, UUID.randomUUID().toString(), "nao-e-uuid")) {
      assertThat(CryptoApi.text(api.create(actor.session(), with(lci(), "contaId", account))))
          .isEqualTo("{\"erro\":\"conta não encontrada\"}");
    }
    assertThat(
            CryptoApi.text(api.create(actor.session(), with(lci(), "contaId", actor.checkingId()))))
        .isEqualTo("{\"erro\":\"conta deve ser de investimento\"}");
    base.send(actor.session(), patch("/contas/" + broker), Map.of("arquivada", true));
    assertThat(CryptoApi.text(api.create(actor.session(), lci())))
        .isEqualTo("{\"erro\":\"conta arquivada\"}");
  }

  @Test
  void editsAndDeletesATitle() throws Exception {
    String id = api.createOk(actor.session(), lci());

    MockHttpServletResponse updated =
        api.update(actor.session(), id, with(with(lci(), "taxa", "98.5"), "nome", "LCI nova"));
    MockHttpServletResponse deleted = api.remove(actor.session(), id);

    assertThat(updated.getStatus()).isEqualTo(200);
    assertThat(CryptoApi.body(updated).get("taxa").asString()).isEqualTo("98.5");
    assertThat(CryptoApi.body(updated).get("nome").asString()).isEqualTo("LCI nova");
    assertThat(deleted.getStatus()).isEqualTo(204);
    assertThat(CryptoApi.body(api.list(actor.session(), "")).get("titulos")).isEmpty();
  }

  @Test
  void savesTheBankValueAsAMarkAndReplacesItOnTheSameDay() throws Exception {
    String id = api.createOk(actor.session(), lci());

    assertThat(api.mark(actor.session(), id, "2034-06-01", 103_000).getStatus()).isEqualTo(201);
    assertThat(api.mark(actor.session(), id, "2034-06-01", 103_500).getStatus()).isEqualTo(201);

    JsonNode listed = CryptoApi.body(api.list(actor.session(), "")).get("titulos").get(0);
    assertThat(listed.get("ultimaMarcacao").get("data").asString()).isEqualTo("2034-06-01");
    assertThat(listed.get("ultimaMarcacao").get("valorCentavos").asLong()).isEqualTo(103_500);
  }

  @Test
  void refusesAMarkBeforeTheInvestmentOrInTheFuture() throws Exception {
    String id = api.createOk(actor.session(), lci());

    for (String date : List.of("2034-01-09", "2034-06-16")) {
      MockHttpServletResponse refused = api.mark(actor.session(), id, date, 100_000);

      assertThat(refused.getStatus()).as(date).isEqualTo(400);
      assertThat(CryptoApi.text(refused))
          .isEqualTo("{\"erro\":\"data deve ficar entre a aplicação e hoje\"}");
    }
  }

  @Test
  void redeemsATitleAndThenRefusesMarksAfterTheRedemption() throws Exception {
    String id = api.createOk(actor.session(), lci());

    MockHttpServletResponse redeemed = api.redeem(actor.session(), id, "2034-05-10", 104_000);

    assertThat(redeemed.getStatus()).isEqualTo(200);
    JsonNode body = CryptoApi.body(redeemed);
    assertThat(body.get("resgate").get("data").asString()).isEqualTo("2034-05-10");
    assertThat(body.get("resgate").get("valorCentavos").asLong()).isEqualTo(104_000);
    assertThat(CryptoApi.text(api.mark(actor.session(), id, "2034-06-01", 1)))
        .isEqualTo("{\"erro\":\"título já resgatado nessa data\"}");
  }

  @Test
  void aTitleOfAnotherUserMissingOrMalformedIs404Everywhere() throws Exception {
    String id = api.createOk(actor.session(), lci());
    CryptoApi.Actor other = base.newActor();
    for (String target : List.of(id, UUID.randomUUID().toString(), "nao-e-uuid")) {
      assertThat(CryptoApi.text(api.update(other.session(), target, lci()))).isEqualTo(NOT_FOUND);
      assertThat(CryptoApi.text(api.remove(other.session(), target))).isEqualTo(NOT_FOUND);
      assertThat(CryptoApi.text(api.mark(other.session(), target, "2034-06-01", 1)))
          .isEqualTo(NOT_FOUND);
      assertThat(CryptoApi.text(api.redeem(other.session(), target, "2034-06-01", 1)))
          .isEqualTo(NOT_FOUND);
    }
    assertThat(CryptoApi.body(api.list(other.session(), "")).get("titulos")).isEmpty();
    assertThat(CryptoApi.body(api.list(actor.session(), "")).get("titulos")).hasSize(1);
  }

  @Test
  void deletingTheAccountTakesItsTitlesAndMarks() throws Exception {
    String id = api.createOk(actor.session(), lci());
    api.mark(actor.session(), id, "2034-06-01", 103_000);
    JsonNode summary =
        CryptoApi.body(
            base.send(actor.session(), get("/contas/" + broker + "/resumo-exclusao"), null));

    MockHttpServletResponse deleted = base.send(actor.session(), delete("/contas/" + broker), null);

    assertThat(summary.get("titulosRendaFixa").asLong()).isEqualTo(1);
    assertThat(deleted.getStatus()).isEqualTo(204);
    assertThat(CryptoApi.body(api.list(actor.session(), "")).get("titulos")).isEmpty();
  }

  @Test
  void withoutSessionIs401() throws Exception {
    assertThat(api.list(null, "").getStatus()).isEqualTo(401);
    assertThat(api.create(null, lci()).getStatus()).isEqualTo(401);
  }
}
