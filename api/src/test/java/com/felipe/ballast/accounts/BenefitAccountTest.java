package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
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
class BenefitAccountTest {

  private static final String NOT_FOUND_CATEGORY = "{\"erro\":\"categoria não encontrada\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private AccountsApi accounts;
  private BenefitApi api;
  private TestUsers.Registered user;
  private Cookie session;
  private String food;
  private String market;
  private String leisure;

  @BeforeEach
  void setUp() throws Exception {
    accounts = new AccountsApi(mvc);
    api = new BenefitApi(mvc);
    TestUsers users = new TestUsers(mvc);
    user = users.register();
    session = users.session(user);
    food = api.categoryId(session, "Alimentação");
    market = api.categoryId(session, "Mercado");
    leisure = api.categoryId(session, "Lazer");
  }

  private long accountCount(Cookie who) throws Exception {
    return BenefitApi.body(accounts.list(who, "")).size();
  }

  private static List<String> idsOf(JsonNode list) {
    List<String> ids = new ArrayList<>();
    list.forEach(item -> ids.add(item.asString()));
    return ids;
  }

  @Test
  void createsABenefitAccountWithoutAListAndEverythingIsAllowed() throws Exception {
    MockHttpServletResponse created = api.create(session, "VR", null);

    assertThat(created.getStatus()).isEqualTo(201);
    JsonNode body = BenefitApi.body(created);
    assertThat(body.get("tipo").asString()).isEqualTo("beneficio");
    assertThat(body.get("categoriasPermitidas").isArray()).isTrue();
    assertThat(body.get("categoriasPermitidas")).isEmpty();
  }

  @Test
  void createsABenefitAccountWithAnEmptyListAsEverythingAllowed() throws Exception {
    MockHttpServletResponse created = api.create(session, "VR", List.of());

    assertThat(created.getStatus()).isEqualTo(201);
    assertThat(BenefitApi.body(created).get("categoriasPermitidas")).isEmpty();
  }

  @Test
  void createsABenefitAccountWithAListAndReadsItBackSortedById() throws Exception {
    MockHttpServletResponse created = api.create(session, "VR", List.of(market, food, leisure));

    assertThat(created.getStatus()).isEqualTo(201);
    JsonNode body = BenefitApi.body(created);
    List<String> ids = idsOf(body.get("categoriasPermitidas"));
    assertThat(ids).containsExactlyInAnyOrder(market, food, leisure);
    assertThat(ids).isSorted();

    String id = body.get("id").asString();
    assertThat(BenefitApi.body(accounts.find(session, id))).isEqualTo(body);
    JsonNode listed = BenefitApi.body(accounts.list(session, "")).get(0);
    assertThat(listed).isEqualTo(body);
  }

  @Test
  void acceptsTheCategoryIdInUpperCase() throws Exception {
    MockHttpServletResponse created = api.create(session, "VR", List.of(food.toUpperCase()));

    assertThat(created.getStatus()).isEqualTo(201);
    assertThat(idsOf(BenefitApi.body(created).get("categoriasPermitidas"))).containsExactly(food);
  }

  @Test
  void otherAccountTypesAnswerNullAndRefuseTheField() throws Exception {
    JsonNode checking = accounts.createOk(session, "Corrente");
    assertThat(checking.get("categoriasPermitidas").isNull()).isTrue();

    for (String type : List.of("corrente", "poupanca", "dinheiro")) {
      MockHttpServletResponse refused =
          accounts.create(
              session,
              "{\"nome\":\"X "
                  + type
                  + "\",\"tipo\":\""
                  + type
                  + "\",\"categoriasPermitidas\":[\""
                  + food
                  + "\"]}");
      assertThat(refused.getStatus()).as(type).isEqualTo(400);
      assertThat(BenefitApi.text(refused))
          .isEqualTo("{\"erro\":\"categoriasPermitidas só vale para conta de benefício\"}");
    }
    MockHttpServletResponse emptyList =
        accounts.create(
            session, "{\"nome\":\"Vazia\",\"tipo\":\"corrente\",\"categoriasPermitidas\":[]}");
    assertThat(emptyList.getStatus()).isEqualTo(400);
    assertThat(accountCount(session)).isEqualTo(1);
  }

  @Test
  void refusesACategoryThatIsNotAnExpenseOfTheUserWithTheSameMessageAndSavesNothing()
      throws Exception {
    TestUsers other = new TestUsers(mvc);
    Cookie otherSession = other.session(other.register());
    String otherUsers = api.categoryId(otherSession, "Mercado");
    String income = api.categoryId(session, "Salário");
    List<String> bad =
        List.of(
            income,
            otherUsers,
            UUID.randomUUID().toString(),
            "nao-e-uuid",
            "1-1-1-1-1",
            food.replace("-", ""));

    for (String id : bad) {
      MockHttpServletResponse refused = api.create(session, "VR", List.of(food, id));

      assertThat(refused.getStatus()).as(id).isEqualTo(400);
      assertThat(BenefitApi.text(refused)).as(id).isEqualTo(NOT_FOUND_CATEGORY);
    }
    assertThat(accountCount(session)).isZero();
  }

  @Test
  void refusesARepeatedCategoryEvenWithDifferentCase() throws Exception {
    MockHttpServletResponse same = api.create(session, "VR", List.of(food, food));
    MockHttpServletResponse differentCase =
        api.create(session, "VR", List.of(food, food.toUpperCase()));

    for (MockHttpServletResponse refused : List.of(same, differentCase)) {
      assertThat(refused.getStatus()).isEqualTo(400);
      assertThat(BenefitApi.text(refused))
          .isEqualTo("{\"erro\":\"categoriasPermitidas não pode repetir categoria\"}");
    }
    assertThat(accountCount(session)).isZero();
  }

  @Test
  void refusesMoreThanFiftyCategoriesAndAcceptsExactlyFifty() throws Exception {
    List<String> fifty = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      fifty.add(newExpenseCategory("Extra " + i));
    }

    MockHttpServletResponse accepted = api.create(session, "Cinquenta", fifty);
    assertThat(accepted.getStatus()).isEqualTo(201);
    assertThat(BenefitApi.body(accepted).get("categoriasPermitidas")).hasSize(50);

    List<String> fiftyOne = new ArrayList<>(fifty);
    fiftyOne.add(food);
    MockHttpServletResponse refused = api.create(session, "Cinquenta e um", fiftyOne);
    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(BenefitApi.text(refused))
        .isEqualTo("{\"erro\":\"categoriasPermitidas deve ter no máximo 50 itens\"}");
    assertThat(accountCount(session)).isEqualTo(1);
  }

  @Test
  void refusesNullItemsAndAListThatIsNotAnArray() throws Exception {
    for (String content :
        List.of(
            "{\"nome\":\"VR\",\"tipo\":\"beneficio\",\"categoriasPermitidas\":[null]}",
            "{\"nome\":\"VR\",\"tipo\":\"beneficio\",\"categoriasPermitidas\":\"" + food + "\"}",
            "{\"nome\":\"VR\",\"tipo\":\"beneficio\",\"categoriasPermitidas\":[1]}",
            "{\"nome\":\"VR\",\"tipo\":\"beneficio\",\"categoriasPermitidas\":[[\""
                + food
                + "\"]]}",
            "{\"nome\":\"VR\",\"tipo\":\"beneficio\",\"categoriasPermitidas\":{}}")) {
      assertThat(api.createRaw(session, content).getStatus()).as(content).isEqualTo(400);
    }
    assertThat(accountCount(session)).isZero();
  }

  @Test
  void replacesTheListAndEmptyingItAllowsEverything() throws Exception {
    String id = api.createOk(session, "VR", List.of(food));

    MockHttpServletResponse swapped = api.putIds(session, id, List.of(market, leisure));

    assertThat(swapped.getStatus()).isEqualTo(200);
    JsonNode body = BenefitApi.body(swapped);
    assertThat(body.get("id").asString()).isEqualTo(id);
    assertThat(idsOf(body.get("categoriasPermitidas"))).containsExactlyInAnyOrder(market, leisure);
    assertThat(BenefitApi.body(accounts.find(session, id))).isEqualTo(body);

    MockHttpServletResponse emptied = api.putIds(session, id, List.of());

    assertThat(emptied.getStatus()).isEqualTo(200);
    assertThat(BenefitApi.body(emptied).get("categoriasPermitidas")).isEmpty();
    assertThat(storedList(id)).isEmpty();
  }

  @Test
  void putIsRepeatableWithTheSameList() throws Exception {
    String id = api.createOk(session, "VR", List.of(food));

    assertThat(api.putIds(session, id, List.of(food)).getStatus()).isEqualTo(200);
    assertThat(api.putIds(session, id, List.of(food)).getStatus()).isEqualTo(200);

    assertThat(storedList(id)).containsExactly(food);
  }

  @Test
  void putOnAnArchivedAccountIs400AndKeepsTheList() throws Exception {
    String id = api.createOk(session, "VR", List.of(food));
    accounts.archive(session, id, "{\"arquivada\":true}");

    MockHttpServletResponse refused = api.putIds(session, id, List.of());

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(BenefitApi.text(refused)).isEqualTo("{\"erro\":\"conta arquivada\"}");
    assertThat(storedList(id)).containsExactly(food);
  }

  @Test
  void putOnAnotherAccountTypeOfTheUserIs400() throws Exception {
    String checking = accounts.createOk(session, "Corrente").get("id").asString();

    MockHttpServletResponse refused = api.putIds(session, checking, List.of(food));

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(BenefitApi.text(refused))
        .isEqualTo("{\"erro\":\"só conta de benefício tem categorias permitidas\"}");
  }

  @Test
  void putOnAnotherUsersAccountOrMissingOrMalformedIs404WithTheSameBody() throws Exception {
    TestUsers other = new TestUsers(mvc);
    Cookie otherSession = other.session(other.register());
    String theirFood = api.categoryId(otherSession, "Alimentação");
    String theirs = api.createOk(otherSession, "VR de B", List.of(theirFood));

    for (String id : List.of(theirs, UUID.randomUUID().toString(), "nao-e-uuid")) {
      MockHttpServletResponse response = api.putIds(session, id, List.of(food));

      assertThat(response.getStatus()).as(id).isEqualTo(404);
      assertThat(BenefitApi.text(response)).as(id).isEqualTo("{\"erro\":\"não encontrado\"}");
    }
    assertThat(storedList(theirs)).containsExactly(theirFood);
  }

  @Test
  void putWithAnotherUsersCategoryIs400AndKeepsTheList() throws Exception {
    TestUsers other = new TestUsers(mvc);
    Cookie otherSession = other.session(other.register());
    String id = api.createOk(session, "VR", List.of(food));

    MockHttpServletResponse refused =
        api.putIds(session, id, List.of(api.categoryId(otherSession, "Mercado")));

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(BenefitApi.text(refused)).isEqualTo(NOT_FOUND_CATEGORY);
    assertThat(storedList(id)).containsExactly(food);
  }

  @Test
  void putRefusesIncomeCategoryRepetitionTooManyAndMissingField() throws Exception {
    String id = api.createOk(session, "VR", List.of(food));

    assertThat(
            BenefitApi.text(api.putIds(session, id, List.of(api.categoryId(session, "Salário")))))
        .isEqualTo(NOT_FOUND_CATEGORY);
    assertThat(BenefitApi.text(api.putIds(session, id, List.of(market, market))))
        .isEqualTo("{\"erro\":\"categoriasPermitidas não pode repetir categoria\"}");
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 51; i++) {
      many.add(UUID.randomUUID().toString());
    }
    assertThat(BenefitApi.text(api.putIds(session, id, many)))
        .isEqualTo("{\"erro\":\"categoriasPermitidas deve ter no máximo 50 itens\"}");
    assertThat(BenefitApi.text(api.putList(session, id, Map.of())))
        .isEqualTo("{\"erro\":\"categoriaIds é obrigatório\"}");
    assertThat(api.putList(session, id, "{\"categoriaIds\":[null]}").getStatus()).isEqualTo(400);
    assertThat(api.putList(session, id, "{\"categoriaIds\":[],\"extra\":1}").getStatus())
        .isEqualTo(400);
    assertThat(storedList(id)).containsExactly(food);
  }

  @Test
  void putWithoutSessionIs401() throws Exception {
    assertThat(api.putIds(null, UUID.randomUUID().toString(), List.of()).getStatus())
        .isEqualTo(401);
  }

  @Test
  void listingShowsEachBenefitsOwnListAndNullForTheOthers() throws Exception {
    String first = api.createOk(session, "VA", List.of(market));
    String second = api.createOk(session, "VR", List.of(food, leisure));
    api.createOk(session, "Livre", null);
    accounts.createOk(session, "Corrente");

    JsonNode listed = BenefitApi.body(accounts.list(session, ""));

    for (JsonNode account : listed) {
      switch (account.get("nome").asString()) {
        case "VA" -> assertThat(idsOf(account.get("categoriasPermitidas"))).containsExactly(market);
        case "VR" ->
            assertThat(idsOf(account.get("categoriasPermitidas")))
                .containsExactlyInAnyOrder(food, leisure);
        case "Livre" -> assertThat(account.get("categoriasPermitidas")).isEmpty();
        default -> assertThat(account.get("categoriasPermitidas").isNull()).isTrue();
      }
    }
    assertThat(first).isNotEqualTo(second);
    assertThat(listed).hasSize(4);
  }

  private List<String> storedList(String accountId) {
    return jdbc.sql(
            "SELECT CAST(categoria_id AS text) FROM conta_categoria_permitida"
                + " WHERE conta_id = :id ORDER BY categoria_id")
        .param("id", UUID.fromString(accountId))
        .query(String.class)
        .list();
  }

  private String newExpenseCategory(String name) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo)"
                + " VALUES (:id, :userId, :name, 'despesa')")
        .param("id", id)
        .param("userId", user.id())
        .param("name", name)
        .update();
    return id.toString();
  }
}
