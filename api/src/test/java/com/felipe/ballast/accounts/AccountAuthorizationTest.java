package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
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
class AccountAuthorizationTest {

  private static final String NOT_FOUND = "{\"erro\":\"não encontrado\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private AccountsApi api;
  private Cookie sessionA;
  private Cookie sessionB;
  private String accountOfB;

  @BeforeEach
  void setUp() throws Exception {
    api = new AccountsApi(mvc);
    TestUsers users = new TestUsers(mvc);
    sessionA = users.session(users.register());
    sessionB = users.session(users.register());
    accountOfB = api.createOk(sessionB, "Conta do B").get("id").asString();
  }

  private String body(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void readingAnotherUsersAccountIs404() throws Exception {
    MockHttpServletResponse response = api.find(sessionA, accountOfB);

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(body(response)).isEqualTo(NOT_FOUND);
  }

  @Test
  void patchingAnotherUsersAccountIs404AndChangesNothing() throws Exception {
    MockHttpServletResponse response = api.setArchived(sessionA, accountOfB, true);

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(body(response)).isEqualTo(NOT_FOUND);
    boolean archived =
        jdbc.sql("SELECT arquivada FROM conta WHERE id = :id")
            .param("id", UUID.fromString(accountOfB))
            .query(Boolean.class)
            .single();
    assertThat(archived).isFalse();
    assertThat(AccountsApi.body(api.find(sessionB, accountOfB)).get("arquivada").asBoolean())
        .isFalse();
  }

  @Test
  void anotherUsersAccountAnswersExactlyLikeANonexistentOne() throws Exception {
    MockHttpServletResponse foreign = api.find(sessionA, accountOfB);
    MockHttpServletResponse missing = api.find(sessionA, AccountsApi.randomId());
    MockHttpServletResponse foreignPatch = api.setArchived(sessionA, accountOfB, true);
    MockHttpServletResponse missingPatch = api.setArchived(sessionA, AccountsApi.randomId(), true);

    assertThat(missing.getStatus()).isEqualTo(foreign.getStatus());
    assertThat(body(missing)).isEqualTo(body(foreign));
    assertThat(missingPatch.getStatus()).isEqualTo(foreignPatch.getStatus());
    assertThat(body(missingPatch)).isEqualTo(body(foreignPatch));
  }

  @Test
  void anIdThatIsNotAUuidIs404WithTheSameBody() throws Exception {
    for (String id :
        new String[] {"abc", "123", "1-1-1-1-1", "0000000000000000000000000000000g", "null"}) {
      MockHttpServletResponse read = api.find(sessionA, id);
      MockHttpServletResponse patch = api.setArchived(sessionA, id, true);

      assertThat(read.getStatus()).as(id).isEqualTo(404);
      assertThat(body(read)).as(id).isEqualTo(NOT_FOUND);
      assertThat(patch.getStatus()).as(id).isEqualTo(404);
      assertThat(body(patch)).as(id).isEqualTo(NOT_FOUND);
    }
  }

  @Test
  void theListOfAOnlyShowsAAccounts() throws Exception {
    JsonNode ownAccount = api.createOk(sessionA, "Conta do A");
    api.setArchived(sessionB, accountOfB, true);
    api.createOk(sessionB, "Outra do B");

    for (String query : new String[] {"", "?arquivadas=false", "?arquivadas=true"}) {
      JsonNode list = AccountsApi.body(api.list(sessionA, query));
      for (JsonNode item : list) {
        assertThat(item.get("id").asString()).isEqualTo(ownAccount.get("id").asString());
      }
    }
    assertThat(AccountsApi.body(api.list(sessionA, "")).size()).isEqualTo(1);
    assertThat(AccountsApi.body(api.list(sessionA, "?arquivadas=true")).size()).isZero();
  }

  @Test
  void aUserIdSentByTheClientDoesNotDecideTheOwner() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                get("/contas")
                    .cookie(sessionA)
                    .queryParam("usuarioId", UUID.randomUUID().toString())
                    .header("X-Usuario-Id", UUID.randomUUID().toString()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(AccountsApi.body(response).size()).isZero();
  }

  @Test
  void everyRouteRequiresASession() throws Exception {
    assertThat(api.create(null, "{\"nome\":\"X\",\"tipo\":\"corrente\"}").getStatus())
        .isEqualTo(401);
    assertThat(api.list(null, "").getStatus()).isEqualTo(401);
    assertThat(api.find(null, accountOfB).getStatus()).isEqualTo(401);
    assertThat(api.setArchived(null, accountOfB, true).getStatus()).isEqualTo(401);
  }
}
