package com.felipe.ballast.fixedincome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.crypto.CryptoApi;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
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
class InvestmentAccountTest {

  private static final String NO_ENTRY =
      "{\"erro\":\"conta de investimento não recebe lançamento\"}";
  private static final String NO_TRANSFER =
      "{\"erro\":\"conta de investimento não participa de transferência\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private CryptoApi api;
  private CryptoApi.Actor actor;
  private String expense;
  private String income;
  private String investment;

  @BeforeEach
  void setUp() throws Exception {
    api = new CryptoApi(mvc);
    actor = api.newActor();
    investment = api.createAccountOk(actor.session(), "Corretora", "investimento");
    expense = categoryId(actor.session(), "Mercado");
    income = categoryId(actor.session(), "Salário");
  }

  private String categoryId(Cookie session, String name) throws Exception {
    for (JsonNode item : CryptoApi.body(api.send(session, get("/categorias"), null))) {
      if (item.get("nome").asString().equals(name)) {
        return item.get("id").asString();
      }
    }
    throw new IllegalStateException("categoria de teste não encontrada: " + name);
  }

  private long entriesOf(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :id")
        .param("id", userId)
        .query(Long.class)
        .single();
  }

  private Map<String, Object> expenseIn(String accountId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tipo", "despesa");
    body.put("titulo", "Mercado");
    body.put("valorCentavos", 1_000);
    body.put("categoriaId", expense);
    body.put("contaId", accountId);
    body.put("data", "2026-03-10");
    return body;
  }

  @Test
  void createsAnInvestmentAccountWithZeroBalanceAndNoCard() throws Exception {
    MockHttpServletResponse created =
        api.send(
            actor.session(),
            post("/contas"),
            Map.of("nome", "Outra corretora", "tipo", "investimento"));

    assertThat(created.getStatus()).isEqualTo(201);
    JsonNode body = CryptoApi.body(created);
    assertThat(body.get("tipo").asString()).isEqualTo("investimento");
    assertThat(body.get("saldoInicialCentavos").asLong()).isZero();
    assertThat(body.get("saldoCentavos").asLong()).isZero();
    assertThat(body.get("cartao").isNull()).isTrue();
    assertThat(body.get("categoriasPermitidas").isNull()).isTrue();
  }

  @Test
  void acceptsAnExplicitZeroInitialBalance() throws Exception {
    MockHttpServletResponse created =
        api.send(
            actor.session(),
            post("/contas"),
            Map.of("nome", "Zero", "tipo", "investimento", "saldoInicialCentavos", 0));

    assertThat(created.getStatus()).isEqualTo(201);
  }

  @Test
  void refusesAnInitialBalanceInReais() throws Exception {
    for (long cents : new long[] {1, -1, 100_000}) {
      MockHttpServletResponse refused =
          api.send(
              actor.session(),
              post("/contas"),
              Map.of("nome", "Com saldo", "tipo", "investimento", "saldoInicialCentavos", cents));

      assertThat(refused.getStatus()).as("%d", cents).isEqualTo(400);
      assertThat(CryptoApi.text(refused))
          .isEqualTo("{\"erro\":\"conta de investimento não tem saldo em reais\"}");
    }
  }

  @Test
  void anEntryInAnInvestmentAccountIs400AndNothingIsSaved() throws Exception {
    MockHttpServletResponse refused =
        api.send(actor.session(), post("/lancamentos"), expenseIn(investment));

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused)).isEqualTo(NO_ENTRY);
    assertThat(entriesOf(actor.user().id())).isZero();
  }

  @Test
  void anIncomeInAnInvestmentAccountIsAlso400() throws Exception {
    Map<String, Object> body = expenseIn(investment);
    body.put("tipo", "receita");
    body.put("categoriaId", income);

    MockHttpServletResponse refused = api.send(actor.session(), post("/lancamentos"), body);

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused)).isEqualTo(NO_ENTRY);
  }

  @Test
  void movingAnEntryToAnInvestmentAccountIs400AndKeepsTheEntry() throws Exception {
    String entryId =
        CryptoApi.expect(
                api.send(actor.session(), post("/lancamentos"), expenseIn(actor.checkingId())), 201)
            .get("id")
            .asString();

    MockHttpServletResponse refused =
        api.send(actor.session(), put("/lancamentos/" + entryId), expenseIn(investment));

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused)).isEqualTo(NO_ENTRY);
    JsonNode kept = CryptoApi.body(api.send(actor.session(), get("/lancamentos/" + entryId), null));
    assertThat(kept.get("contaId").asString()).isEqualTo(actor.checkingId());
  }

  @Test
  void anInvestmentAccountOnEitherSideOfATransferIs400AndNothingIsSaved() throws Exception {
    for (String[] sides :
        new String[][] {{investment, actor.checkingId()}, {actor.checkingId(), investment}}) {
      MockHttpServletResponse refused =
          api.send(
              actor.session(),
              post("/transferencias"),
              Map.of(
                  "contaOrigemId",
                  sides[0],
                  "contaDestinoId",
                  sides[1],
                  "valorCentavos",
                  1_000,
                  "data",
                  "2026-03-10"));

      assertThat(refused.getStatus()).isEqualTo(400);
      assertThat(CryptoApi.text(refused)).isEqualTo(NO_TRANSFER);
    }
    assertThat(entriesOf(actor.user().id())).isZero();
  }

  @Test
  void anInvestmentAccountOfAnotherUserStillAnswersAccountNotFound() throws Exception {
    CryptoApi.Actor other = api.newActor();
    String otherInvestment = api.createAccountOk(other.session(), "Corretora", "investimento");

    MockHttpServletResponse entry =
        api.send(actor.session(), post("/lancamentos"), expenseIn(otherInvestment));
    MockHttpServletResponse transfer =
        api.send(
            actor.session(),
            post("/transferencias"),
            Map.of(
                "contaOrigemId",
                actor.checkingId(),
                "contaDestinoId",
                otherInvestment,
                "valorCentavos",
                1_000,
                "data",
                "2026-03-10"));

    assertThat(CryptoApi.text(entry)).isEqualTo("{\"erro\":\"conta não encontrada\"}");
    assertThat(CryptoApi.text(transfer)).isEqualTo("{\"erro\":\"conta não encontrada\"}");
  }

  @Test
  void aRecurrenceInAnInvestmentAccountIs400AndNothingIsSaved() throws Exception {
    Map<String, Object> body = expenseIn(investment);
    body.remove("data");
    body.put("regra", Map.of("frequencia", "mensal", "diaDoMes", 5, "inicio", "2026-01-01"));

    MockHttpServletResponse refused = api.send(actor.session(), post("/recorrencias"), body);

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused)).isEqualTo(NO_ENTRY);
    long templates =
        jdbc.sql("SELECT count(*) FROM template_lancamento WHERE usuario_id = :id")
            .param("id", actor.user().id())
            .query(Long.class)
            .single();
    assertThat(templates).isZero();
  }

  @Test
  void anInstallmentPurchaseInAnInvestmentAccountIsRefusedAsNotACard() throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("titulo", "Notebook");
    body.put("valorTotalCentavos", 100_000);
    body.put("parcelas", 3);
    body.put("categoriaId", expense);
    body.put("contaId", investment);
    body.put("data", "2026-03-10");

    MockHttpServletResponse refused = api.send(actor.session(), post("/compras-parceladas"), body);

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused))
        .isEqualTo("{\"erro\":\"compra parcelada só em cartão de crédito\"}");
  }
}
