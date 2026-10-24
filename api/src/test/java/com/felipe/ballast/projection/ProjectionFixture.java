package com.felipe.ballast.projection;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Monta o cenário por SQL, para o teste não depender das rotas de escrita e poder criar o que a
// API não cria (prevista avulsa, vencida, realizado com data futura).
final class ProjectionFixture {

  static final JsonMapper JSON = JsonMapper.builder().build();

  record Actor(TestUsers.Registered user, Cookie session) {}

  private final MockMvc mvc;
  private final JdbcClient jdbc;

  ProjectionFixture(MockMvc mvc, JdbcClient jdbc) {
    this.mvc = mvc;
    this.jdbc = jdbc;
  }

  Actor newActor() throws Exception {
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered user = users.register();
    return new Actor(user, users.session(user));
  }

  UUID account(Actor actor, long initialCents, boolean archived) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO conta (id, usuario_id, nome, tipo, saldo_inicial, arquivada)
            VALUES (:id, :userId, :name, 'corrente', :initial, :archived)
            """)
        .param("id", id)
        .param("userId", actor.user().id())
        .param("name", "Conta " + id)
        .param("initial", BigDecimal.valueOf(initialCents, 2))
        .param("archived", archived)
        .update();
    return id;
  }

  UUID account(Actor actor, long initialCents) {
    return account(actor, initialCents, false);
  }

  void income(Actor actor, UUID account, String status, long cents, LocalDate cashDate) {
    entry(actor, account, "receita", status, cents, cashDate);
  }

  void expense(Actor actor, UUID account, String status, long cents, LocalDate cashDate) {
    entry(actor, account, "despesa", status, cents, cashDate);
  }

  private void entry(
      Actor actor, UUID account, String type, String status, long cents, LocalDate cashDate) {
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,
                                    data_competencia, data_caixa, status)
            SELECT :id, :userId, :type, 'teste', c.id, :account, :amount, :date, :date, :status
            FROM categoria c WHERE c.usuario_id = :userId AND c.tipo = :type
            ORDER BY c.id LIMIT 1
            """)
        .param("id", UUID.randomUUID())
        .param("userId", actor.user().id())
        .param("type", type)
        .param("account", account)
        .param("amount", BigDecimal.valueOf(cents, 2))
        .param("date", cashDate)
        .param("status", status)
        .update();
  }

  // Receita na categoria Salário da semente, a que a F19 usa para achar o próximo salário.
  void salary(Actor actor, UUID account, String status, long cents, LocalDate cashDate) {
    incomeIn(actor, account, "Salário", status, cents, cashDate);
  }

  // Receita numa categoria escolhida pelo nome. income() pega a primeira por id, que pode ser
  // Salário.
  void incomeIn(
      Actor actor, UUID account, String category, String status, long cents, LocalDate cashDate) {
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,
                                    data_competencia, data_caixa, status)
            SELECT :id, :userId, 'receita', 'teste', c.id, :account, :amount, :date, :date,
                   :status
            FROM categoria c WHERE c.usuario_id = :userId AND c.nome = :category
            """)
        .param("id", UUID.randomUUID())
        .param("userId", actor.user().id())
        .param("category", category)
        .param("account", account)
        .param("amount", BigDecimal.valueOf(cents, 2))
        .param("date", cashDate)
        .param("status", status)
        .update();
  }

  UUID benefitAccount(Actor actor, String name, long initialCents) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO conta (id, usuario_id, nome, tipo, saldo_inicial)
            VALUES (:id, :userId, :name, 'beneficio', :initial)
            """)
        .param("id", id)
        .param("userId", actor.user().id())
        .param("name", name)
        .param("initial", BigDecimal.valueOf(initialCents, 2))
        .update();
    return id;
  }

  MockHttpServletResponse spendable(Cookie session) throws Exception {
    var request = get("/posso-gastar");
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  JsonNode spendableOk(Cookie session) throws Exception {
    MockHttpServletResponse response = spendable(session);
    if (response.getStatus() != 200) {
      throw new IllegalStateException(
          "posso gastar falhou: " + response.getStatus() + " " + text(response));
    }
    return body(response);
  }

  void transfer(Actor actor, UUID from, UUID to, long cents, LocalDate cashDate) {
    UUID outId = UUID.randomUUID();
    UUID inId = UUID.randomUUID();
    leg(actor, outId, from, cents, cashDate);
    leg(actor, inId, to, cents, cashDate);
    jdbc.sql(
            """
            INSERT INTO transferencia (id, usuario_id, saida_id, entrada_id, conta_saida_id,
                                       conta_entrada_id)
            VALUES (:id, :userId, :outId, :inId, :from, :to)
            """)
        .param("id", UUID.randomUUID())
        .param("userId", actor.user().id())
        .param("outId", outId)
        .param("inId", inId)
        .param("from", from)
        .param("to", to)
        .update();
  }

  private void leg(Actor actor, UUID id, UUID account, long cents, LocalDate cashDate) {
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,
                                    data_competencia, data_caixa, status)
            VALUES (:id, :userId, 'transferencia', 'teste', NULL, :account, :amount, :date, :date,
                    'realizado')
            """)
        .param("id", id)
        .param("userId", actor.user().id())
        .param("account", account)
        .param("amount", BigDecimal.valueOf(cents, 2))
        .param("date", cashDate)
        .update();
  }

  MockHttpServletResponse projection(Cookie session, String query) throws Exception {
    var request = get("/saldo-projetado" + query);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  // Chama, exige 200 e devolve o corpo.
  JsonNode projectionOk(Cookie session, String query) throws Exception {
    MockHttpServletResponse response = projection(session, query);
    if (response.getStatus() != 200) {
      throw new IllegalStateException(
          "projeção falhou: " + response.getStatus() + " " + text(response));
    }
    return body(response);
  }

  long accountBalance(Cookie session, UUID account) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(get("/contas/" + account).cookie(session)).andReturn().getResponse();
    return body(response).get("saldoCentavos").asLong();
  }

  static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(text(response));
  }

  static String text(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString(StandardCharsets.UTF_8);
  }

  static String error(String message) {
    return "{\"erro\":\"" + message + "\"}";
  }
}
