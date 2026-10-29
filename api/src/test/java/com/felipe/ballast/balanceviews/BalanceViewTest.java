package com.felipe.ballast.balanceviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class BalanceViewTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
  private Cookie sessionA;
  private Cookie sessionB;

  @BeforeEach
  void setUp() throws Exception {
    TestUsers users = new TestUsers(mvc);
    sessionA = users.session(users.register());
    sessionB = users.session(users.register());
  }

  private MockHttpServletResponse send(
      Cookie session, MockHttpServletRequestBuilder request, Object body) throws Exception {
    request.cookie(session);
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private JsonNode list(Cookie session) throws Exception {
    MockHttpServletResponse response = send(session, get("/visoes-saldo"), null);
    assertThat(response.getStatus()).isEqualTo(200);
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private int create(Cookie session, String title, List<String> types) throws Exception {
    return send(session, post("/visoes-saldo"), Map.of("titulo", title, "tipos", types))
        .getStatus();
  }

  @Test
  void theDefaultViewIsCreatedAtSignUpWithoutCreditCards() throws Exception {
    // Ainda sem nenhuma leitura: a visão já existe porque nasceu no cadastro.
    assertThat(jdbc.sql("SELECT count(*) FROM visao_saldo").query(Long.class).single())
        .isGreaterThanOrEqualTo(2L);
    JsonNode views = list(sessionA);

    assertThat(views).hasSize(1);
    assertThat(views.get(0).get("titulo").asString()).isEqualTo("Total sem cartão");
    List<String> types = views.get(0).get("tipos").valueStream().map(JsonNode::asString).toList();
    assertThat(types)
        .containsExactlyInAnyOrder(
            "corrente", "poupanca", "dinheiro", "beneficio", "cripto", "investimento", "cofrinho");
    // Ler de novo não cria outra.
    assertThat(list(sessionA)).hasSize(1);
  }

  @Test
  void createsEditsAndDeletesAView() throws Exception {
    list(sessionA);
    assertThat(create(sessionA, "Bancos", List.of("corrente", "poupanca", "corrente")))
        .isEqualTo(204);
    JsonNode views = list(sessionA);
    assertThat(views).hasSize(2);
    JsonNode banks = views.get(1);
    assertThat(banks.get("tipos")).hasSize(2);
    String id = banks.get("id").asString();

    assertThat(
            send(
                    sessionA,
                    put("/visoes-saldo/" + id),
                    Map.of("titulo", "Reserva", "tipos", List.of("cofrinho")))
                .getStatus())
        .isEqualTo(204);
    JsonNode edited = list(sessionA).get(1);
    assertThat(edited.get("titulo").asString()).isEqualTo("Reserva");
    assertThat(edited.get("tipos").get(0).asString()).isEqualTo("cofrinho");

    assertThat(send(sessionA, delete("/visoes-saldo/" + id), null).getStatus()).isEqualTo(204);
    assertThat(list(sessionA)).hasSize(1);
  }

  @Test
  void theDefaultViewCanBeEditedToo() throws Exception {
    String id = list(sessionA).get(0).get("id").asString();

    assertThat(
            send(
                    sessionA,
                    put("/visoes-saldo/" + id),
                    Map.of("titulo", "Tudo", "tipos", List.of("corrente", "cartao_credito")))
                .getStatus())
        .isEqualTo(204);

    assertThat(list(sessionA).get(0).get("titulo").asString()).isEqualTo("Tudo");
  }

  @Test
  void theLastViewCannotBeDeleted() throws Exception {
    String id = list(sessionA).get(0).get("id").asString();

    assertThat(send(sessionA, delete("/visoes-saldo/" + id), null).getStatus()).isEqualTo(409);
    assertThat(list(sessionA)).hasSize(1);
  }

  @Test
  void stopsAtTenViewsPerUser() throws Exception {
    list(sessionA);
    for (int i = 0; i < 9; i++) {
      assertThat(create(sessionA, "Visão " + i, List.of("corrente"))).isEqualTo(204);
    }
    assertThat(create(sessionA, "Demais", List.of("corrente"))).isEqualTo(409);
    assertThat(list(sessionA)).hasSize(10);
  }

  @Test
  void anotherUsersViewIsNotFound() throws Exception {
    String id = list(sessionB).get(0).get("id").asString();
    assertThat(create(sessionB, "Outra", List.of("dinheiro"))).isEqualTo(204);

    assertThat(
            send(
                    sessionA,
                    put("/visoes-saldo/" + id),
                    Map.of("titulo", "X", "tipos", List.of("corrente")))
                .getStatus())
        .isEqualTo(404);
    // A usuária A ainda tem uma visão só (a que a leitura criou): não é "a última" que decide aqui.
    list(sessionA);
    assertThat(send(sessionA, delete("/visoes-saldo/" + id), null).getStatus()).isIn(404, 409);
    assertThat(list(sessionB)).hasSize(2);
    assertThat(list(sessionB).get(0).get("titulo").asString()).isEqualTo("Total sem cartão");
  }

  @Test
  void rejectsBadInput() throws Exception {
    assertThat(create(sessionA, "   ", List.of("corrente"))).isEqualTo(400);
    assertThat(create(sessionA, "Ok", List.of())).isEqualTo(400);
    assertThat(create(sessionA, "Ok", List.of("moeda"))).isEqualTo(400);
    assertThat(create(sessionA, "x".repeat(101), List.of("corrente"))).isEqualTo(400);
  }
}
