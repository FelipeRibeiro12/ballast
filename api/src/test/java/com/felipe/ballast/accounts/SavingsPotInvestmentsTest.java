package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class SavingsPotInvestmentsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private AccountsApi api;
  private TestUsers.Registered userA;
  private Cookie sessionA;
  private Cookie sessionB;

  @BeforeEach
  void setUp() throws Exception {
    api = new AccountsApi(mvc);
    TestUsers users = new TestUsers(mvc);
    userA = users.register();
    sessionA = users.session(userA);
    sessionB = users.session(users.register());
  }

  private String create(Cookie session, String type, long initialCents) throws Exception {
    MockHttpServletResponse response =
        api.create(
            session,
            "{\"nome\":\"Conta "
                + UUID.randomUUID()
                + "\",\"tipo\":\""
                + type
                + "\",\"saldoInicialCentavos\":"
                + initialCents
                + "}");
    assertThat(response.getStatus()).isEqualTo(201);
    return AccountsApi.body(response).get("id").asString();
  }

  private MockHttpServletResponse mark(Cookie session, String id, String body) throws Exception {
    return mvc.perform(
            put("/contas/" + id + "/investimentos")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse mark(Cookie session, String id, boolean value) throws Exception {
    return mark(session, id, "{\"emInvestimentos\":" + value + "}");
  }

  private JsonNode netWorth(String date) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(get("/patrimonio?data=" + date).cookie(sessionA)).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private boolean flagInList(String id) throws Exception {
    for (JsonNode item : AccountsApi.body(api.list(sessionA, ""))) {
      if (item.get("id").asString().equals(id)) {
        return item.get("emInvestimentos").asBoolean();
      }
    }
    throw new IllegalStateException("conta fora da lista");
  }

  @Test
  void startsUnmarkedAndTogglesBothWays() throws Exception {
    String pot = create(sessionA, "cofrinho", 100_00);
    assertThat(flagInList(pot)).isFalse();

    MockHttpServletResponse on = mark(sessionA, pot, true);
    assertThat(on.getStatus()).isEqualTo(200);
    assertThat(AccountsApi.body(on).get("emInvestimentos").asBoolean()).isTrue();
    assertThat(flagInList(pot)).isTrue();
    // Repetir é idempotente.
    assertThat(mark(sessionA, pot, true).getStatus()).isEqualTo(200);

    assertThat(mark(sessionA, pot, false).getStatus()).isEqualTo(200);
    assertThat(flagInList(pot)).isFalse();
  }

  @Test
  void aUserCannotTouchAnotherUsersAccount() throws Exception {
    String pot = create(sessionA, "cofrinho", 0);

    assertThat(mark(sessionB, pot, true).getStatus()).isEqualTo(404);
    assertThat(mark(sessionB, UUID.randomUUID().toString(), true).getStatus()).isEqualTo(404);
    assertThat(mark(sessionB, "nao-e-uuid", true).getStatus()).isEqualTo(404);
    assertThat(flagInList(pot)).isFalse();
  }

  @Test
  void refusesAccountsThatAreNotSavingsPots() throws Exception {
    String checking = create(sessionA, "corrente", 0);

    assertThat(mark(sessionA, checking, true).getStatus()).isEqualTo(400);
    assertThat(mark(sessionA, checking, false).getStatus()).isEqualTo(400);
  }

  @Test
  void rejectsMissingUnknownAndNonBooleanFields() throws Exception {
    String pot = create(sessionA, "cofrinho", 0);

    assertThat(mark(sessionA, pot, "{}").getStatus()).isEqualTo(400);
    assertThat(mark(sessionA, pot, "{\"emInvestimentos\":true,\"x\":1}").getStatus())
        .isEqualTo(400);
    assertThat(mark(sessionA, pot, "{\"emInvestimentos\":\"sim\"}").getStatus()).isEqualTo(400);
  }

  @Test
  void theDatabaseRefusesTheFlagOnOtherTypes() throws Exception {
    String checking = create(sessionA, "corrente", 0);

    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE conta SET em_investimentos = true WHERE id = :id")
                    .param("id", UUID.fromString(checking))
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void markedPotsAreReportedWithoutDoublingTheGlobalTotal() throws Exception {
    create(sessionA, "corrente", 50_00);
    String marked = create(sessionA, "cofrinho", 200_00);
    create(sessionA, "cofrinho", 700_00);

    JsonNode before = netWorth("2026-06-01");
    assertThat(before.get("cofrinhosCentavos").asLong()).isZero();
    assertThat(before.get("cofrinhos")).isEmpty();
    assertThat(before.get("totalCentavos").asLong()).isEqualTo(950_00);

    mark(sessionA, marked, true);
    JsonNode after = netWorth("2026-06-01");

    assertThat(after.get("cofrinhosCentavos").asLong()).isEqualTo(200_00);
    assertThat(after.get("cofrinhos")).hasSize(1);
    assertThat(after.get("cofrinhos").get(0).get("contaId").asString()).isEqualTo(marked);
    assertThat(after.get("cofrinhos").get(0).get("saldoCentavos").asLong()).isEqualTo(200_00);
    assertThat(after.get("contasCentavos").asLong()).isEqualTo(950_00);
    assertThat(after.get("totalCentavos").asLong()).isEqualTo(950_00);
  }

  @Test
  void anArchivedMarkedPotLeavesTheCardAndCannotBeMarkedAgain() throws Exception {
    String pot = create(sessionA, "cofrinho", 300_00);
    mark(sessionA, pot, true);
    assertThat(api.setArchived(sessionA, pot, true).getStatus()).isEqualTo(200);

    JsonNode body = netWorth("2026-06-01");
    assertThat(body.get("cofrinhosCentavos").asLong()).isZero();
    assertThat(body.get("cofrinhos")).isEmpty();
    assertThat(mark(sessionA, pot, true).getStatus()).isEqualTo(400);
    assertThat(mark(sessionA, pot, false).getStatus()).isEqualTo(200);
  }

  @Test
  void aPastDateUsesTheBalanceOfThatDate() throws Exception {
    String pot = create(sessionA, "cofrinho", 100_00);
    mark(sessionA, pot, true);
    UUID category = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :userId, 'Teste',"
                + " 'receita')")
        .param("id", category)
        .param("userId", userA.id())
        .update();
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,
                                    data_competencia, data_caixa)
            VALUES (:id, :userId, 'receita', 'rendimento', :categoryId, :accountId, :amount,
                    DATE '2026-03-10', DATE '2026-03-10')
            """)
        .param("id", UUID.randomUUID())
        .param("userId", userA.id())
        .param("categoryId", category)
        .param("accountId", UUID.fromString(pot))
        .param("amount", new BigDecimal("25.00"))
        .update();

    assertThat(netWorth("2026-03-09").get("cofrinhosCentavos").asLong()).isEqualTo(100_00);
    assertThat(netWorth("2026-03-10").get("cofrinhosCentavos").asLong()).isEqualTo(125_00);
    assertThat(netWorth("2026-03-10").get("totalCentavos").asLong()).isEqualTo(125_00);
  }
}
