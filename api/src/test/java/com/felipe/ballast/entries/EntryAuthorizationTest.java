package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.expenseBody;
import static com.felipe.ballast.entries.EntriesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Usuário A pedindo recurso de B, em toda rota.
@IntegrationTest
class EntryAuthorizationTest {

  private static final String NOT_FOUND = "{\"erro\":\"não encontrado\"}";
  private static final String PERIOD = "?de=2026-01-01&ate=2026-12-31";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private EntriesApi api;
  private EntriesApi.Actor a;
  private EntriesApi.Actor b;
  private JsonNode entryOfB;
  private String idOfB;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    a = api.newActor();
    b = api.newActor();
    entryOfB = api.createOk(b.session(), expenseBody(b));
    idOfB = entryOfB.get("id").asString();
  }

  private long countAll() {
    return jdbc.sql("SELECT count(*) FROM lancamento").query(Long.class).single();
  }

  @Test
  void readingAnotherUsersEntryIs404() throws Exception {
    MockHttpServletResponse response = api.find(a.session(), idOfB);

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(EntriesApi.text(response)).isEqualTo(NOT_FOUND);
  }

  @Test
  void updatingAnotherUsersEntryIs404AndChangesNothing() throws Exception {
    // O corpo é válido para A (conta e categoria de A), só o lançamento é de B.
    MockHttpServletResponse response =
        api.update(a.session(), idOfB, with(expenseBody(a), "titulo", "invadido"));

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(EntriesApi.text(response)).isEqualTo(NOT_FOUND);
    assertThat(EntriesApi.body(api.find(b.session(), idOfB))).isEqualTo(entryOfB);
    assertThat(
            jdbc.sql("SELECT titulo FROM lancamento WHERE id = :id")
                .param("id", UUID.fromString(idOfB))
                .query(String.class)
                .single())
        .isEqualTo("Mercado do mês");
  }

  @Test
  void deletingAnotherUsersEntryIs404AndRemovesNothing() throws Exception {
    long before = countAll();

    MockHttpServletResponse response = api.remove(a.session(), idOfB);

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(EntriesApi.text(response)).isEqualTo(NOT_FOUND);
    assertThat(countAll()).isEqualTo(before);
    assertThat(api.find(b.session(), idOfB).getStatus()).isEqualTo(200);
  }

  @Test
  void anotherUsersEntryAnswersExactlyLikeANonexistentOne() throws Exception {
    String missing = EntriesApi.randomId();

    for (MockHttpServletResponse[] pair :
        List.of(
            new MockHttpServletResponse[] {
              api.find(a.session(), idOfB), api.find(a.session(), missing)
            },
            new MockHttpServletResponse[] {
              api.update(a.session(), idOfB, expenseBody(a)),
              api.update(a.session(), missing, expenseBody(a))
            },
            new MockHttpServletResponse[] {
              api.remove(a.session(), idOfB), api.remove(a.session(), missing)
            })) {
      assertThat(pair[1].getStatus()).isEqualTo(pair[0].getStatus());
      assertThat(EntriesApi.text(pair[1])).isEqualTo(EntriesApi.text(pair[0]));
    }
  }

  @Test
  void anIdThatIsNotAUuidIs404WithTheSameBodyInEveryRoute() throws Exception {
    for (String id :
        new String[] {"abc", "123", "1-1-1-1-1", "0000000000000000000000000000000g", "null"}) {
      assertThat(api.find(a.session(), id).getStatus()).as(id).isEqualTo(404);
      assertThat(EntriesApi.text(api.find(a.session(), id))).as(id).isEqualTo(NOT_FOUND);
      assertThat(api.update(a.session(), id, expenseBody(a)).getStatus()).as(id).isEqualTo(404);
      assertThat(EntriesApi.text(api.update(a.session(), id, expenseBody(a))))
          .as(id)
          .isEqualTo(NOT_FOUND);
      assertThat(api.remove(a.session(), id).getStatus()).as(id).isEqualTo(404);
      assertThat(EntriesApi.text(api.remove(a.session(), id))).as(id).isEqualTo(NOT_FOUND);
    }
  }

  @Test
  void theListOfAOnlyShowsAEntries() throws Exception {
    JsonNode own = api.createOk(a.session(), expenseBody(a));

    JsonNode list = EntriesApi.body(api.list(a.session(), PERIOD));

    assertThat(list).hasSize(1);
    assertThat(list.get(0).get("id").asString()).isEqualTo(own.get("id").asString());
  }

  @Test
  void filteringByAnotherUsersAccountShowsNothingOfB() throws Exception {
    MockHttpServletResponse response = api.list(a.session(), PERIOD + "&contaId=" + b.accountId());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(EntriesApi.text(response)).isEqualTo("[]");
  }

  @Test
  void creatingWithAnotherUsersAccountIs400AndSavesNothing() throws Exception {
    long before = countAll();

    MockHttpServletResponse response =
        api.create(a.session(), with(expenseBody(a), "contaId", b.accountId()));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"conta não encontrada\"}");
    assertThat(countAll()).isEqualTo(before);
  }

  @Test
  void creatingWithAnotherUsersCategoryIs400AndSavesNothing() throws Exception {
    long before = countAll();

    MockHttpServletResponse response =
        api.create(a.session(), with(expenseBody(a), "categoriaId", b.expenseCategoryId()));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"categoria não encontrada\"}");
    assertThat(countAll()).isEqualTo(before);
  }

  @Test
  void movingToAnotherUsersAccountOrCategoryIs400AndChangesNothing() throws Exception {
    JsonNode own = api.createOk(a.session(), expenseBody(a));
    String id = own.get("id").asString();

    assertThat(
            api.update(a.session(), id, with(expenseBody(a), "contaId", b.accountId())).getStatus())
        .isEqualTo(400);
    assertThat(
            api.update(a.session(), id, with(expenseBody(a), "categoriaId", b.expenseCategoryId()))
                .getStatus())
        .isEqualTo(400);
    assertThat(EntriesApi.body(api.find(a.session(), id))).isEqualTo(own);
  }

  @Test
  void anEntryOfBNeverEntersTheBalanceOfA() throws Exception {
    assertThat(api.accountBalance(a.session(), a.accountId())).isZero();
    assertThat(api.accountBalance(b.session(), b.accountId())).isEqualTo(-12_345L);
  }

  @Test
  void anOwnerIdSentByTheClientIsRejectedAsAnUndeclaredField() throws Exception {
    long before = countAll();

    MockHttpServletResponse response =
        api.create(a.session(), with(expenseBody(a), "usuarioId", b.user().id().toString()));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(countAll()).isEqualTo(before);
  }

  @Test
  void everyRouteRequiresASession() throws Exception {
    assertThat(api.create(null, expenseBody(a)).getStatus()).isEqualTo(401);
    assertThat(api.list(null, PERIOD).getStatus()).isEqualTo(401);
    assertThat(api.find(null, idOfB).getStatus()).isEqualTo(401);
    assertThat(api.update(null, idOfB, expenseBody(b)).getStatus()).isEqualTo(401);
    assertThat(api.remove(null, idOfB).getStatus()).isEqualTo(401);
    assertThat(
            mvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/categorias"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
  }
}
