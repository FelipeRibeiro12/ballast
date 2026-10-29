package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class SavingsPotTest {

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

  private MockHttpServletResponse send(Cookie session, String path, Object body) throws Exception {
    var request = path.endsWith("/cofrinho") ? put(path) : post(path);
    return mvc.perform(
            request
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  private String newAccount(Cookie session, String type) throws Exception {
    MockHttpServletResponse response =
        send(session, "/contas", Map.of("nome", "Conta " + type, "tipo", type));
    assertThat(response.getStatus()).isEqualTo(201);
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("id").asString();
  }

  @Test
  void aSavingsPotStartsWithoutPercentAndTakesOne() throws Exception {
    String id = newAccount(sessionA, "cofrinho");

    MockHttpServletResponse response =
        send(sessionA, "/contas/" + id + "/cofrinho", Map.of("percentualCdi", "110.5"));

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    assertThat(body.get("percentualCdi").asString()).isEqualTo("110.50");
    assertThat(body.get("tipo").asString()).isEqualTo("cofrinho");
  }

  @Test
  void refusesPercentOnOtherTypesAndBadValues() throws Exception {
    String checking = newAccount(sessionA, "corrente");
    String pot = newAccount(sessionA, "cofrinho");

    assertThat(
            send(sessionA, "/contas/" + checking + "/cofrinho", Map.of("percentualCdi", "100"))
                .getStatus())
        .isEqualTo(400);
    for (String bad :
        new String[] {"0", "0.00", "1001", "1000.01", "1000.50", "abc", "-1", "100.123", ""}) {
      assertThat(
              send(sessionA, "/contas/" + pot + "/cofrinho", Map.of("percentualCdi", bad))
                  .getStatus())
          .as(bad)
          .isEqualTo(400);
    }
  }

  @Test
  void anotherUsersPotIsNotFound() throws Exception {
    String pot = newAccount(sessionB, "cofrinho");

    assertThat(
            send(sessionA, "/contas/" + pot + "/cofrinho", Map.of("percentualCdi", "100"))
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  void acceptsTheLimitsOfTheRange() throws Exception {
    String pot = newAccount(sessionA, "cofrinho");

    for (String ok : new String[] {"0.01", "1000", "1000.00"}) {
      assertThat(
              send(sessionA, "/contas/" + pot + "/cofrinho", Map.of("percentualCdi", ok))
                  .getStatus())
          .as(ok)
          .isEqualTo(200);
    }
  }
}
