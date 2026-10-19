package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class AccountListingTest {

  @Autowired MockMvc mvc;
  private AccountsApi api;
  private Cookie session;

  @BeforeEach
  void setUp() throws Exception {
    api = new AccountsApi(mvc);
    TestUsers users = new TestUsers(mvc);
    session = users.session(users.register());
  }

  private List<String> names(String query) throws Exception {
    MockHttpServletResponse response = api.list(session, query);
    assertThat(response.getStatus()).isEqualTo(200);
    List<String> names = new ArrayList<>();
    for (JsonNode item : AccountsApi.body(response)) {
      names.add(item.get("nome").asString());
    }
    return names;
  }

  @Test
  void aNewUserHasAnEmptyList() throws Exception {
    MockHttpServletResponse response = api.list(session, "");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("[]");
  }

  @Test
  void ordersByNameIgnoringCase() throws Exception {
    api.createOk(session, "bravo");
    api.createOk(session, "Charlie");
    api.createOk(session, "alfa");
    api.createOk(session, "Delta");

    assertThat(names("")).containsExactly("alfa", "bravo", "Charlie", "Delta");
  }

  @Test
  void archivingHidesFromTheDefaultListAndShowsInTheArchivedOne() throws Exception {
    api.createOk(session, "Fica");
    String leaving = api.createOk(session, "Sai").get("id").asString();

    api.setArchived(session, leaving, true);

    assertThat(names("")).containsExactly("Fica");
    assertThat(names("?arquivadas=false")).containsExactly("Fica");
    assertThat(names("?arquivadas=true")).containsExactly("Sai");
  }

  @Test
  void unarchivingBringsTheAccountBack() throws Exception {
    api.createOk(session, "Fica");
    String id = api.createOk(session, "Volta").get("id").asString();
    api.setArchived(session, id, true);

    MockHttpServletResponse response = api.setArchived(session, id, false);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(AccountsApi.body(response).get("arquivada").asBoolean()).isFalse();
    assertThat(names("")).containsExactly("Fica", "Volta");
    assertThat(names("?arquivadas=true")).isEmpty();
  }

  @Test
  void patchingTwiceWithTheSameValueAnswers200AndTheSameAccount() throws Exception {
    String id = api.createOk(session, "Idempotente").get("id").asString();

    MockHttpServletResponse first = api.setArchived(session, id, true);
    MockHttpServletResponse second = api.setArchived(session, id, true);

    assertThat(first.getStatus()).isEqualTo(200);
    assertThat(second.getStatus()).isEqualTo(200);
    assertThat(AccountsApi.body(second)).isEqualTo(AccountsApi.body(first));
    assertThat(AccountsApi.body(second).get("arquivada").asBoolean()).isTrue();

    MockHttpServletResponse thirdFalse = api.setArchived(session, id, false);
    MockHttpServletResponse fourthFalse = api.setArchived(session, id, false);
    assertThat(AccountsApi.body(fourthFalse)).isEqualTo(AccountsApi.body(thirdFalse));
  }

  @Test
  void archivedAccountsStillAnswerByIdWithTheirBalance() throws Exception {
    MockHttpServletResponse created =
        api.create(
            session, "{\"nome\":\"Velha\",\"tipo\":\"poupanca\",\"saldoInicialCentavos\":777}");
    String id = AccountsApi.body(created).get("id").asString();
    api.setArchived(session, id, true);

    JsonNode body = AccountsApi.body(api.find(session, id));

    assertThat(body.get("arquivada").asBoolean()).isTrue();
    assertThat(body.get("saldoCentavos").asLong()).isEqualTo(777L);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "?arquivadas=1",
        "?arquivadas=yes",
        "?arquivadas=on",
        "?arquivadas=",
        "?arquivadas=TRUE",
        "?arquivadas=talvez",
        "?arquivadas=true&arquivadas=false"
      })
  void anyArchivedValueOtherThanTrueOrFalseIs400(String query) throws Exception {
    MockHttpServletResponse response = api.list(session, query);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .contains("\"erro\"")
        .doesNotContain("talvez");
  }

  @Test
  void patchRejectsAnInvalidBody() throws Exception {
    String id = api.createOk(session, "Alvo").get("id").asString();

    for (String body :
        new String[] {
          "{}",
          "{\"arquivada\":null}",
          "{\"arquivada\":\"true\"}",
          "{\"arquivada\":1}",
          "{\"arquivada\":true,\"nome\":\"Outro\"}",
          "{\"nome\":\"Outro\"}"
        }) {
      assertThat(api.archive(session, id, body).getStatus()).as(body).isEqualTo(400);
    }
    assertThat(AccountsApi.body(api.find(session, id)).get("nome").asString()).isEqualTo("Alvo");
  }

  @Test
  void patchRequiresJson() throws Exception {
    String id = api.createOk(session, "Alvo").get("id").asString();

    var request =
        patch("/contas/" + id)
            .contentType(MediaType.TEXT_PLAIN)
            .content("{\"arquivada\":true}")
            .cookie(session);

    assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(415);
  }
}
