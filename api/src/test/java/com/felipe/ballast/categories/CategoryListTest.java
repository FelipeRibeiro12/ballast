package com.felipe.ballast.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class CategoryListTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  private Cookie sessionA;
  private Cookie sessionB;

  @BeforeEach
  void setUp() throws Exception {
    TestUsers users = new TestUsers(mvc);
    sessionA = users.session(users.register());
    sessionB = users.session(users.register());
  }

  private MockHttpServletResponse list(Cookie session, String query) throws Exception {
    var request = get("/categorias" + query);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private JsonNode listOk(Cookie session, String query) throws Exception {
    MockHttpServletResponse response = list(session, query);
    assertThat(response.getStatus()).isEqualTo(200);
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void listsTheSixteenSeedsOrderedByTypeThenNameIgnoringCase() throws Exception {
    JsonNode body = listOk(sessionA, "");

    List<String> actual = new ArrayList<>();
    for (JsonNode item : body) {
      actual.add(item.get("tipo").asString() + ":" + item.get("nome").asString());
    }
    List<String> expected =
        CategorySeeds.ALL.stream()
            .map(seed -> seed.type().value() + ":" + seed.name())
            .sorted(
                Comparator.comparing((String s) -> s.substring(0, s.indexOf(':')))
                    .thenComparing(s -> s.substring(s.indexOf(':') + 1).toLowerCase()))
            .toList();
    assertThat(actual).hasSize(16).containsExactlyElementsOf(expected);
  }

  @Test
  void eachItemHasTheDocumentedFields() throws Exception {
    JsonNode first = listOk(sessionA, "").get(0);

    assertThat(first.propertyNames())
        .containsExactlyInAnyOrder("id", "nome", "tipo", "paiId", "cor", "icone", "protegida");
    assertThat(first.get("id").asString()).matches("[0-9a-f-]{36}");
    assertThat(first.get("paiId").isNull()).isTrue();
    assertThat(first.get("cor").isNull()).isTrue();
    assertThat(first.get("icone").isNull()).isTrue();
  }

  @Test
  void filtersByType() throws Exception {
    JsonNode income = listOk(sessionA, "?tipo=receita");
    JsonNode expense = listOk(sessionA, "?tipo=despesa");

    assertThat(income).hasSize(5);
    assertThat(expense).hasSize(11);
    for (JsonNode item : income) {
      assertThat(item.get("tipo").asString()).isEqualTo("receita");
    }
    for (JsonNode item : expense) {
      assertThat(item.get("tipo").asString()).isEqualTo("despesa");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "?tipo=",
        "?tipo=transferencia",
        "?tipo=Receita",
        "?tipo=RECEITA",
        "?tipo=1",
        "?tipo=receita&tipo=despesa",
        "?tipo=%20receita"
      })
  void anInvalidTypeIs400(String query) throws Exception {
    MockHttpServletResponse response = list(sessionA, query);

    assertThat(response.getStatus()).as(query).isEqualTo(400);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .as(query)
        .isEqualTo("{\"erro\":\"tipo deve ser receita ou despesa\"}");
  }

  @Test
  void theListOfANeverShowsTheCategoriesOfB() throws Exception {
    Set<String> idsOfA = new HashSet<>();
    for (JsonNode item : listOk(sessionA, "")) {
      idsOfA.add(item.get("id").asString());
    }
    Set<String> idsOfB = new HashSet<>();
    for (JsonNode item : listOk(sessionB, "")) {
      idsOfB.add(item.get("id").asString());
    }

    assertThat(idsOfA).hasSize(16).doesNotContainAnyElementsOf(idsOfB);
    assertThat(idsOfB).hasSize(16);
  }

  @Test
  void requiresASession() throws Exception {
    assertThat(list(null, "").getStatus()).isEqualTo(401);
  }
}
