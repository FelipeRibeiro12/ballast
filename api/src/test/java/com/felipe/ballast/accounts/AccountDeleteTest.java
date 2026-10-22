package com.felipe.ballast.accounts;

import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.crypto.CryptoApi;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class AccountDeleteTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private CryptoApi api;
  private CryptoApi.Actor actor;
  private Cookie session;
  private String checking;
  private String expense;
  private String income;

  @BeforeEach
  void setUp() throws Exception {
    api = new CryptoApi(mvc);
    actor = api.newActor();
    session = actor.session();
    checking = actor.checkingId();
    expense = categoryId("Mercado");
    income = categoryId("Salário");
  }

  private String categoryId(String name) throws Exception {
    for (JsonNode item : CryptoApi.body(api.send(session, get("/categorias"), null))) {
      if (item.get("nome").asString().equals(name)) {
        return item.get("id").asString();
      }
    }
    throw new IllegalStateException("categoria de teste não encontrada: " + name);
  }

  private String categoryName(String id) throws Exception {
    for (JsonNode item : CryptoApi.body(api.send(session, get("/categorias"), null))) {
      if (item.get("id").asString().equals(id)) {
        return item.get("nome").asString();
      }
    }
    return null;
  }

  private String createdId(MockHttpServletResponse response) throws Exception {
    if (response.getStatus() != 201) {
      throw new IllegalStateException(
          "chamada de teste falhou: " + response.getStatus() + " " + CryptoApi.text(response));
    }
    return CryptoApi.body(response).get("id").asString();
  }

  // O POST /recorrencias devolve {recorrencia, ocorrenciasGeradas}.
  private String recurrenceId(MockHttpServletResponse response) throws Exception {
    if (response.getStatus() != 201) {
      throw new IllegalStateException("recorrência de teste falhou: " + CryptoApi.text(response));
    }
    return CryptoApi.body(response).get("recorrencia").get("id").asString();
  }

  private String entry(String accountId, String type, long cents) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tipo", type);
    body.put("titulo", "Teste");
    body.put("valorCentavos", cents);
    body.put("categoriaId", type.equals("receita") ? income : expense);
    body.put("contaId", accountId);
    body.put("data", "2026-03-10");
    return createdId(api.send(session, post("/lancamentos"), body));
  }

  private String transfer(String from, String to, long cents) throws Exception {
    return createdId(
        api.send(
            session,
            post("/transferencias"),
            Map.of(
                "contaOrigemId",
                from,
                "contaDestinoId",
                to,
                "valorCentavos",
                cents,
                "data",
                "2026-03-10",
                "titulo",
                "Guardar")));
  }

  private String card(String name, String paymentId) throws Exception {
    return createdId(
        api.send(
            session,
            post("/contas"),
            Map.of(
                "nome",
                name,
                "tipo",
                "cartao_credito",
                "cartao",
                Map.of(
                    "limiteCentavos",
                    100_000,
                    "diaFechamento",
                    10,
                    "diaVencimento",
                    20,
                    "contaPagamentoId",
                    paymentId))));
  }

  private long balance(String accountId) throws Exception {
    return CryptoApi.body(api.send(session, get("/contas/" + accountId), null))
        .get("saldoCentavos")
        .asLong();
  }

  private MockHttpServletResponse remove(Cookie who, String accountId) throws Exception {
    return api.send(who, delete("/contas/" + accountId), null);
  }

  private long count(String table, String column, String id) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :id::uuid")
        .param("id", id)
        .query(Long.class)
        .single();
  }

  @Test
  void deletesTheAccountWithItsEntriesRecurrencesAndAnswers404Afterwards() throws Exception {
    String savings = api.createAccountOk(session, "Poupança", "poupanca");
    entry(savings, "receita", 50_000);
    entry(savings, "despesa", 10_000);
    Map<String, Object> recurrence = new LinkedHashMap<>();
    recurrence.put("tipo", "despesa");
    recurrence.put("titulo", "Aluguel");
    recurrence.put("valorCentavos", 150_000);
    recurrence.put("categoriaId", expense);
    recurrence.put("contaId", savings);
    recurrence.put("regra", Map.of("frequencia", "mensal", "diaDoMes", 5, "inicio", "2026-01-01"));
    String template = recurrenceId(api.send(session, post("/recorrencias"), recurrence));

    MockHttpServletResponse deleted = remove(session, savings);

    assertThat(deleted.getStatus()).isEqualTo(204);
    assertThat(api.send(session, get("/contas/" + savings), null).getStatus()).isEqualTo(404);
    assertThat(count("lancamento", "conta_id", savings)).isZero();
    assertThat(count("template_lancamento", "id", template)).isZero();
    assertThat(count("conta", "id", savings)).isZero();
  }

  @Test
  void aTransferToAnotherAccountTurnsItsLegIntoAnIncomeAndKeepsItsBalance() throws Exception {
    String savings = api.createAccountOk(session, "Poupança", "poupanca");
    entry(checking, "receita", 100_000);
    transfer(checking, savings, 30_000);
    long savingsBefore = balance(savings);

    assertThat(remove(session, checking).getStatus()).isEqualTo(204);

    assertThat(balance(savings)).isEqualTo(savingsBefore).isEqualTo(30_000);
    JsonNode legs =
        CryptoApi.body(
            api.send(
                session,
                get("/lancamentos?de=2026-03-01&ate=2026-03-31&contaId=" + savings),
                null));
    assertThat(legs).hasSize(1);
    JsonNode leg = legs.get(0);
    assertThat(leg.get("tipo").asString()).isEqualTo("receita");
    assertThat(leg.get("titulo").asString()).isEqualTo("Guardar");
    assertThat(leg.get("valorCentavos").asLong()).isEqualTo(30_000);
    assertThat(leg.get("transferenciaId").isNull()).isTrue();
    assertThat(categoryName(leg.get("categoriaId").asString())).isEqualTo("Outras receitas");
    assertThat(count("transferencia", "usuario_id", actor.user().id().toString())).isZero();
  }

  @Test
  void aTransferFromAnotherAccountTurnsItsLegIntoAnExpense() throws Exception {
    String savings = api.createAccountOk(session, "Poupança", "poupanca");
    entry(savings, "receita", 100_000);
    transfer(savings, checking, 40_000);

    assertThat(remove(session, checking).getStatus()).isEqualTo(204);

    assertThat(balance(savings)).isEqualTo(60_000);
    JsonNode legs =
        CryptoApi.body(
            api.send(
                session,
                get("/lancamentos?de=2026-03-01&ate=2026-03-31&contaId=" + savings),
                null));
    JsonNode out = null;
    for (JsonNode item : legs) {
      if (item.get("titulo").asString().equals("Guardar")) {
        out = item;
      }
    }
    assertThat(out).isNotNull();
    assertThat(out.get("tipo").asString()).isEqualTo("despesa");
    assertThat(categoryName(out.get("categoriaId").asString())).isEqualTo("Outros");
  }

  @Test
  void deletingACardKeepsThePaymentsInTheCheckingAccount() throws Exception {
    entry(checking, "receita", 100_000);
    String cardId = card("Cartão", checking);
    entry(cardId, "despesa", 20_000);
    transfer(checking, cardId, 20_000);
    Map<String, Object> purchase = new LinkedHashMap<>();
    purchase.put("titulo", "Notebook");
    purchase.put("valorTotalCentavos", 90_000);
    purchase.put("parcelas", 3);
    purchase.put("categoriaId", expense);
    purchase.put("contaId", cardId);
    purchase.put("data", "2026-03-10");
    createdId(api.send(session, post("/compras-parceladas"), purchase));
    long checkingBefore = balance(checking);

    assertThat(remove(session, cardId).getStatus()).isEqualTo(204);

    assertThat(balance(checking)).isEqualTo(checkingBefore).isEqualTo(80_000);
    assertThat(count("cartao", "conta_id", cardId)).isZero();
    assertThat(count("lancamento", "conta_id", cardId)).isZero();
  }

  @Test
  void anAccountThatPaysACardIsRefusedAndNothingIsDeleted() throws Exception {
    String cardId = card("Cartão", checking);
    entry(checking, "receita", 1_000);

    MockHttpServletResponse refused = remove(session, checking);

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused))
        .isEqualTo("{\"erro\":\"esta conta paga um cartão; troque a conta de pagamento antes\"}");
    assertThat(balance(checking)).isEqualTo(1_000);

    assertThat(remove(session, cardId).getStatus()).isEqualTo(204);
    assertThat(remove(session, checking).getStatus()).isEqualTo(204);
  }

  @Test
  void anAccountThatPaidACardInvoiceIsRefusedAndTheInvoiceStaysPaid() throws Exception {
    String savings = api.createAccountOk(session, "Poupança", "poupanca");
    entry(savings, "receita", 100_000);
    String cardId = card("Cartão", checking);
    entry(cardId, "despesa", 20_000);
    transfer(savings, cardId, 20_000);
    String invoicesPath = "/contas/" + cardId + "/faturas?de=2026-03-01&ate=2026-04-30";
    JsonNode before = CryptoApi.body(api.send(session, get(invoicesPath), null));

    MockHttpServletResponse refused = remove(session, savings);

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused))
        .isEqualTo(
            "{\"erro\":\"esta conta pagou fatura de cartão; apagar desmontaria a fatura."
                + " Arquive a conta\"}");
    assertThat(CryptoApi.body(api.send(session, get(invoicesPath), null))).isEqualTo(before);
    assertThat(balance(savings)).isEqualTo(80_000);
  }

  @Test
  void anOccurrenceMovedToAnotherAccountStaysAsAPlainEntry() throws Exception {
    String savings = api.createAccountOk(session, "Poupança", "poupanca");
    Map<String, Object> recurrence = new LinkedHashMap<>();
    recurrence.put("tipo", "despesa");
    recurrence.put("titulo", "Aluguel");
    recurrence.put("valorCentavos", 150_000);
    recurrence.put("categoriaId", expense);
    recurrence.put("contaId", savings);
    recurrence.put("regra", Map.of("frequencia", "mensal", "diaDoMes", 5, "inicio", "2026-01-01"));
    String template = recurrenceId(api.send(session, post("/recorrencias"), recurrence));
    String occurrence =
        jdbc.sql(
                "SELECT id::text FROM lancamento WHERE template_id = :id::uuid"
                    + " ORDER BY ocorrencia_data LIMIT 1")
            .param("id", template)
            .query(String.class)
            .single();
    JsonNode current = CryptoApi.body(api.send(session, get("/lancamentos/" + occurrence), null));
    Map<String, Object> moved = new LinkedHashMap<>();
    moved.put("tipo", "despesa");
    moved.put("titulo", "Aluguel");
    moved.put("valorCentavos", 150_000);
    moved.put("categoriaId", expense);
    moved.put("contaId", checking);
    moved.put("data", current.get("dataCompetencia").asString());
    assertThat(api.send(session, put("/lancamentos/" + occurrence), moved).getStatus())
        .isEqualTo(200);

    assertThat(remove(session, savings).getStatus()).isEqualTo(204);

    JsonNode kept = CryptoApi.body(api.send(session, get("/lancamentos/" + occurrence), null));
    assertThat(kept.get("contaId").asString()).isEqualTo(checking);
    assertThat(kept.get("templateId").isNull()).isTrue();
  }

  @Test
  void deletesACryptoAccountWithItsMovementsAndKeepsTheAsset() throws Exception {
    String bitcoin = api.createAssetOk(session, CryptoApi.bitcoin());
    api.moveOk(session, movement(actor.cryptoId(), bitcoin, "compra", "1", "2026-03-01", 100_000));

    assertThat(remove(session, actor.cryptoId()).getStatus()).isEqualTo(204);

    assertThat(count("movimento_cripto", "conta_id", actor.cryptoId())).isZero();
    assertThat(CryptoApi.body(api.listAssets(session))).hasSize(1);
  }

  @Test
  void anArchivedAccountCanBeDeleted() throws Exception {
    String savings = api.createAccountOk(session, "Poupança", "poupanca");
    api.send(session, patch("/contas/" + savings), Map.of("arquivada", true));

    assertThat(remove(session, savings).getStatus()).isEqualTo(204);
  }

  @Test
  void anAccountOfAnotherUserMissingOrMalformedIs404AndNothingIsDeleted() throws Exception {
    CryptoApi.Actor other = api.newActor();
    for (String id : List.of(other.checkingId(), UUID.randomUUID().toString(), "nao-e-uuid")) {
      MockHttpServletResponse refused = remove(session, id);

      assertThat(refused.getStatus()).as(id).isEqualTo(404);
      assertThat(CryptoApi.text(refused)).isEqualTo("{\"erro\":\"não encontrado\"}");
      assertThat(api.send(session, get("/contas/" + id + "/resumo-exclusao"), null).getStatus())
          .isEqualTo(404);
    }
    assertThat(count("conta", "id", other.checkingId())).isEqualTo(1);
  }

  @Test
  void theDeletionSummaryCountsWhatGoesAlong() throws Exception {
    String savings = api.createAccountOk(session, "Poupança", "poupanca");
    entry(checking, "receita", 100_000);
    entry(checking, "despesa", 1_000);
    transfer(checking, savings, 10_000);

    JsonNode summary =
        CryptoApi.body(api.send(session, get("/contas/" + checking + "/resumo-exclusao"), null));

    assertThat(summary.get("lancamentos").asLong()).isEqualTo(2);
    assertThat(summary.get("transferencias").asLong()).isEqualTo(1);
    assertThat(summary.get("recorrencias").asLong()).isZero();
    assertThat(summary.get("movimentosCripto").asLong()).isZero();
  }

  @Test
  void withoutSessionIs401() throws Exception {
    assertThat(remove(null, checking).getStatus()).isEqualTo(401);
  }
}
