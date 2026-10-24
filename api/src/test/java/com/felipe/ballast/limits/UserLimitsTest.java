package com.felipe.ballast.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.TestcontainersConfiguration;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Contexto próprio, com tetos baixos: os da suíte inteira são generosos (application.properties de
// teste) e não estourariam. Contas: 6, para caber as quatro de montagem mais duas.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.recurrence.job-enabled=false",
      "ballast.indices.job-enabled=false",
      "ballast.cotacoes.job-enabled=false",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1",
      "ballast.limits.accounts=6",
      "ballast.limits.active-recurrences=2",
      "ballast.limits.installment-purchases=2",
      "ballast.limits.fixed-income-titles=2",
      "ballast.limits.crypto-movements=2",
      "ballast.limits.crypto-assets=2",
      "ballast.limits.quotes=2",
      "ballast.limits.account-delete-timeout=500ms"
    })
@AutoConfigureMockMvc
class UserLimitsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate transaction;

  private Cookie session;
  private String checkingId;
  private String cardId;
  private String cryptoId;
  private String investmentId;
  private String expenseCategoryId;
  private String incomeCategoryId;

  @BeforeEach
  void setUp() throws Exception {
    session = newSession();
    checkingId = account(session, "Corrente", "corrente", null);
    cryptoId = account(session, "Exchange", "cripto", null);
    investmentId = account(session, "Corretora", "investimento", null);
    cardId =
        account(
            session,
            "Cartão",
            "cartao_credito",
            Map.of(
                "limiteCentavos",
                500_000,
                "diaFechamento",
                10,
                "diaVencimento",
                20,
                "contaPagamentoId",
                checkingId));
    expenseCategoryId = categoryId(session, "Outros");
    incomeCategoryId = categoryId(session, "Outras receitas");
  }

  private Cookie newSession() throws Exception {
    TestUsers users = new TestUsers(mvc);
    return users.session(users.register());
  }

  private MockHttpServletResponse send(
      Cookie who, MockHttpServletRequestBuilder request, Object body) throws Exception {
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    }
    return mvc.perform(request.cookie(who)).andReturn().getResponse();
  }

  private static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private String account(Cookie who, String name, String type, Object card) throws Exception {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("nome", name);
    request.put("tipo", type);
    if (card != null) {
      request.put("cartao", card);
    }
    MockHttpServletResponse response = send(who, post("/contas"), request);
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    return body(response).get("id").asString();
  }

  private String categoryId(Cookie who, String name) throws Exception {
    for (JsonNode item : body(send(who, get("/categorias"), null))) {
      if (item.get("nome").asString().equals(name)) {
        return item.get("id").asString();
      }
    }
    throw new IllegalStateException("categoria não encontrada: " + name);
  }

  private void assertLimit(MockHttpServletResponse response, String what) throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
    assertThat(body(response).get("erro").asString()).isEqualTo("limite de " + what + " atingido");
  }

  private Map<String, Object> recurrence(String title) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tipo", "despesa");
    body.put("titulo", title);
    body.put("valorCentavos", 1_000);
    body.put("categoriaId", expenseCategoryId);
    body.put("contaId", checkingId);
    body.put("regra", Map.of("frequencia", "mensal", "diaDoMes", 5, "inicio", "2026-01-01"));
    return body;
  }

  private Map<String, Object> purchase() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("titulo", "Notebook");
    body.put("valorTotalCentavos", 100_000);
    body.put("parcelas", 3);
    body.put("categoriaId", expenseCategoryId);
    body.put("contaId", cardId);
    body.put("data", "2026-03-10");
    return body;
  }

  private Map<String, Object> title() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaId", investmentId);
    body.put("tipo", "lci");
    body.put("nome", "LCI");
    body.put("valorAplicadoCentavos", 100_000);
    body.put("dataAplicacao", "2026-01-10");
    body.put("indexador", "cdi");
    body.put("taxa", "95");
    return body;
  }

  // Ethereum com contrato: ativo nativo de uma rede só existe um por usuário.
  private Map<String, Object> assetBody(String symbol, char digit) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("rede", "ethereum");
    request.put("contrato", "0x" + String.valueOf(digit).repeat(40));
    request.put("simbolo", symbol);
    request.put("nome", symbol);
    request.put("decimais", 8);
    return request;
  }

  private String asset(Cookie who, String symbol, char digit) throws Exception {
    Map<String, Object> request = assetBody(symbol, digit);
    MockHttpServletResponse response = send(who, post("/ativos-cripto"), request);
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    return body(response).get("id").asString();
  }

  private Map<String, Object> movement(String assetId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contaId", cryptoId);
    body.put("ativoId", assetId);
    body.put("tipo", "compra");
    body.put("quantidade", "1");
    body.put("data", "2026-03-10");
    body.put("custoUsdCentavos", 100);
    return body;
  }

  @Test
  void accountsStopAtTheLimitOfTheUser() throws Exception {
    send(session, post("/contas"), Map.of("nome", "Quinta", "tipo", "corrente"));
    send(session, post("/contas"), Map.of("nome", "Sexta", "tipo", "corrente"));

    assertLimit(
        send(session, post("/contas"), Map.of("nome", "Sétima", "tipo", "corrente")), "contas");
  }

  @Test
  void theLimitIsPerUserNotGlobal() throws Exception {
    send(session, post("/contas"), Map.of("nome", "Quinta", "tipo", "corrente"));
    send(session, post("/contas"), Map.of("nome", "Sexta", "tipo", "corrente"));

    Cookie other = newSession();

    assertThat(
            send(other, post("/contas"), Map.of("nome", "Primeira", "tipo", "corrente"))
                .getStatus())
        .isEqualTo(201);
  }

  @Test
  void concurrentAccountCreationNeverGoesOverTheLimit() throws Exception {
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered owner = users.register();
    Cookie fresh = users.session(owner);
    int attempts = 12;
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      String name = "Conta " + i;
      results.add(
          pool.submit(
              () -> {
                start.await();
                return send(fresh, post("/contas"), Map.of("nome", name, "tipo", "corrente"))
                    .getStatus();
              }));
    }
    start.countDown();
    int created = 0;
    int refused = 0;
    for (Future<Integer> result : results) {
      int status = result.get();
      if (status == 201) {
        created++;
      } else if (status == 409) {
        refused++;
      }
    }
    pool.shutdown();

    assertThat(created).isEqualTo(6);
    assertThat(refused).isEqualTo(attempts - 6);
    assertThat(
            jdbc.sql("SELECT count(*) FROM conta WHERE usuario_id = :id")
                .param("id", owner.id())
                .query(Long.class)
                .single())
        .isEqualTo(6);
  }

  @Test
  void activeRecurrencesStopAtTheLimitAndReactivationCounts() throws Exception {
    String first =
        body(send(session, post("/recorrencias"), recurrence("A")))
            .get("recorrencia")
            .get("id")
            .asString();
    send(session, post("/recorrencias"), recurrence("B"));

    assertLimit(send(session, post("/recorrencias"), recurrence("C")), "recorrências ativas");

    // Desativar libera a vaga, e reativar tem de respeitar o teto de novo.
    send(session, patch("/recorrencias/" + first), Map.of("ativo", false));
    String third =
        body(send(session, post("/recorrencias"), recurrence("C")))
            .get("recorrencia")
            .get("id")
            .asString();
    assertThat(third).isNotNull();
    assertLimit(
        send(session, patch("/recorrencias/" + first), Map.of("ativo", true)),
        "recorrências ativas");
  }

  @Test
  void installmentPurchasesStopAtTheLimit() throws Exception {
    send(session, post("/compras-parceladas"), purchase());
    send(session, post("/compras-parceladas"), purchase());

    assertLimit(send(session, post("/compras-parceladas"), purchase()), "compras parceladas");
  }

  @Test
  void fixedIncomeTitlesStopAtTheLimit() throws Exception {
    send(session, post("/titulos-renda-fixa"), title());
    send(session, post("/titulos-renda-fixa"), title());

    assertLimit(send(session, post("/titulos-renda-fixa"), title()), "títulos de renda fixa");
  }

  @Test
  void cryptoAssetsMovementsAndQuotesStopAtTheirLimits() throws Exception {
    String btc = asset(session, "AAA", 'a');
    asset(session, "BBB", 'b');
    Map<String, Object> third = assetBody("CCC", 'c');
    assertLimit(send(session, post("/ativos-cripto"), third), "ativos de cripto");

    send(session, post("/movimentos-cripto"), movement(btc));
    send(session, post("/movimentos-cripto"), movement(btc));
    assertLimit(send(session, post("/movimentos-cripto"), movement(btc)), "movimentos de cripto");

    String quotes = "/ativos-cripto/" + btc + "/cotacoes";
    send(session, post(quotes), Map.of("precoUsd", "1", "momento", "2026-10-21T15:00:00Z"));
    send(session, post(quotes), Map.of("precoUsd", "2", "momento", "2026-10-22T15:00:00Z"));
    assertLimit(
        send(session, post(quotes), Map.of("precoUsd", "3", "momento", "2026-10-23T15:00:00Z")),
        "cotações");
  }

  @Test
  void aSlowAccountDeletionTimesOutWith503AndChangesNothing() throws Exception {
    UUID accountId = UUID.fromString(checkingId);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    // Outra transação segura a conta; o DELETE espera por ela e estoura o statement_timeout de
    // 500 ms antes do lock_timeout de 2 s.
    Future<?> holder =
        pool.submit(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      jdbc.sql("SELECT id FROM conta WHERE id = :id FOR UPDATE")
                          .param("id", accountId)
                          .query(UUID.class)
                          .list();
                      locked.countDown();
                      try {
                        release.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
    locked.await();
    MockHttpServletResponse response;
    long started = System.nanoTime();
    try {
      response = send(session, delete("/contas/" + checkingId), null);
    } finally {
      release.countDown();
      holder.get();
      pool.shutdown();
    }

    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(503);
    assertThat(body(response).get("erro").asString()).isEqualTo("serviço indisponível");
    // Abaixo do lock_timeout de 2 s: quem estourou foi o statement_timeout.
    assertThat(elapsedMillis).isLessThan(1_500);
    assertThat(send(session, get("/contas/" + checkingId), null).getStatus()).isEqualTo(200);
  }
}
