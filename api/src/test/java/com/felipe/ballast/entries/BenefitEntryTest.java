package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.expenseBody;
import static com.felipe.ballast.entries.EntriesApi.incomeBody;
import static com.felipe.ballast.entries.EntriesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.accounts.BenefitApi;
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

// Regra do ADR-034 nos caminhos de lançamento: despesa em conta de benefício com lista só aceita
// categoria da lista; receita e as outras contas não são restritas.
@IntegrationTest
class BenefitEntryTest {

  private static final String NOT_PERMITTED = "{\"erro\":\"categoria não permitida nesta conta\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private EntriesApi api;
  private BenefitApi benefits;
  private EntriesApi.Actor actor;
  private String food;
  private String market;
  private String leisure;
  private String benefit;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    benefits = new BenefitApi(mvc);
    actor = api.newActor();
    food = api.categoryId(actor.session(), "Alimentação");
    market = actor.expenseCategoryId();
    leisure = api.categoryId(actor.session(), "Lazer");
    benefit = benefits.createOk(actor.session(), "VR", List.of(food, market));
  }

  private Map<String, Object> expenseIn(String accountId, String categoryId) {
    return with(with(expenseBody(actor), "contaId", accountId), "categoriaId", categoryId);
  }

  private long entriesOf(String accountId) {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE conta_id = :id")
        .param("id", UUID.fromString(accountId))
        .query(Long.class)
        .single();
  }

  @Test
  void anExpenseOutsideTheListIs400AndNothingIsSaved() throws Exception {
    MockHttpServletResponse response = api.create(actor.session(), expenseIn(benefit, leisure));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo(NOT_PERMITTED);
    assertThat(entriesOf(benefit)).isZero();
  }

  @Test
  void anExpenseInsideTheListIs201() throws Exception {
    assertThat(api.create(actor.session(), expenseIn(benefit, food)).getStatus()).isEqualTo(201);
    assertThat(api.create(actor.session(), expenseIn(benefit, market)).getStatus()).isEqualTo(201);

    assertThat(entriesOf(benefit)).isEqualTo(2);
  }

  @Test
  void anExpenseWithAnUnknownCategoryKeepsTheCategoryNotFoundMessage() throws Exception {
    MockHttpServletResponse response =
        api.create(actor.session(), expenseIn(benefit, UUID.randomUUID().toString()));

    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"categoria não encontrada\"}");
  }

  @Test
  void aBenefitWithoutAListAcceptsAnyExpenseCategory() throws Exception {
    String free = benefits.createOk(actor.session(), "VA", null);

    assertThat(api.create(actor.session(), expenseIn(free, leisure)).getStatus()).isEqualTo(201);
  }

  @Test
  void anIncomeInABenefitAccountAcceptsAnyCategoryEvenWithAList() throws Exception {
    MockHttpServletResponse response =
        api.create(
            actor.session(),
            with(
                with(incomeBody(actor), "contaId", benefit),
                "categoriaId",
                actor.incomeCategoryId()));

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(api.accountBalance(actor.session(), benefit)).isEqualTo(12_345);
  }

  @Test
  void otherAccountTypesAreNotRestricted() throws Exception {
    assertThat(api.create(actor.session(), expenseIn(actor.accountId(), leisure)).getStatus())
        .isEqualTo(201);
  }

  @Test
  void putMovingTheCategoryOutsideTheListIs400AndKeepsTheEntry() throws Exception {
    String id = api.createOk(actor.session(), expenseIn(benefit, food)).get("id").asString();

    MockHttpServletResponse response = api.update(actor.session(), id, expenseIn(benefit, leisure));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo(NOT_PERMITTED);
    assertThat(EntriesApi.body(api.find(actor.session(), id)).get("categoriaId").asString())
        .isEqualTo(food);
  }

  @Test
  void putMovingTheCategoryInsideTheListIs200() throws Exception {
    String id = api.createOk(actor.session(), expenseIn(benefit, food)).get("id").asString();

    assertThat(api.update(actor.session(), id, expenseIn(benefit, market)).getStatus())
        .isEqualTo(200);
  }

  @Test
  void putMovingAnExpenseToTheBenefitWithACategoryOutsideTheListIs400() throws Exception {
    String id =
        api.createOk(actor.session(), expenseIn(actor.accountId(), leisure)).get("id").asString();

    MockHttpServletResponse response = api.update(actor.session(), id, expenseIn(benefit, leisure));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo(NOT_PERMITTED);
    JsonNode stored = EntriesApi.body(api.find(actor.session(), id));
    assertThat(stored.get("contaId").asString()).isEqualTo(actor.accountId());
  }

  @Test
  void putMovingAnExpenseToTheBenefitWithACategoryInsideTheListIs200() throws Exception {
    String id =
        api.createOk(actor.session(), expenseIn(actor.accountId(), food)).get("id").asString();

    assertThat(api.update(actor.session(), id, expenseIn(benefit, food)).getStatus())
        .isEqualTo(200);
  }

  @Test
  void putTurningAnIncomeIntoAnExpenseOutsideTheListIs400() throws Exception {
    String id =
        api.createOk(
                actor.session(),
                with(
                    with(incomeBody(actor), "contaId", benefit),
                    "categoriaId",
                    actor.incomeCategoryId()))
            .get("id")
            .asString();

    MockHttpServletResponse response = api.update(actor.session(), id, expenseIn(benefit, leisure));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo(NOT_PERMITTED);
  }

  @Test
  void editingAnOldEntryWhoseCategoryLeftTheListIs400BecauseThePutRewritesTheCategory()
      throws Exception {
    String id = api.createOk(actor.session(), expenseIn(benefit, food)).get("id").asString();
    assertThat(benefits.putIds(actor.session(), benefit, List.of(market)).getStatus())
        .isEqualTo(200);

    MockHttpServletResponse response = api.update(actor.session(), id, expenseIn(benefit, food));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo(NOT_PERMITTED);
  }

  @Test
  void changingTheListDoesNotTouchEntriesAlreadySaved() throws Exception {
    String id = api.createOk(actor.session(), expenseIn(benefit, food)).get("id").asString();

    benefits.putIds(actor.session(), benefit, List.of(market));

    assertThat(api.find(actor.session(), id).getStatus()).isEqualTo(200);
    assertThat(entriesOf(benefit)).isEqualTo(1);
    assertThat(api.accountBalance(actor.session(), benefit)).isEqualTo(-12_345);
  }

  @Test
  void confirmingAnOccurrenceWithAdjustmentWhoseCategoryLeftTheListStillWorks() throws Exception {
    // O ajuste muda valor e data, nunca a categoria, e o gatilho só olha tipo, conta e categoria.
    String id =
        jdbc.sql(
                """
                INSERT INTO lancamento (id, usuario_id, tipo, titulo, valor, categoria_id, conta_id,
                                        data_competencia, data_caixa, status)
                VALUES (:id, :userId, 'despesa', 'Previsto', 10.00, :category, :account,
                        DATE '2026-03-10', DATE '2026-03-10', 'previsto')
                RETURNING CAST(id AS text)
                """)
            .param("id", UUID.randomUUID())
            .param("userId", actor.user().id())
            .param("category", UUID.fromString(food))
            .param("account", UUID.fromString(benefit))
            .query(String.class)
            .single();
    benefits.putIds(actor.session(), benefit, List.of(market));

    MockHttpServletResponse response =
        api.patch(
            actor.session(),
            id,
            Map.of("status", "realizado", "valorCentavos", 2_500, "data", "2026-03-12"));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(EntriesApi.body(response).get("status").asString()).isEqualTo("realizado");
  }
}
