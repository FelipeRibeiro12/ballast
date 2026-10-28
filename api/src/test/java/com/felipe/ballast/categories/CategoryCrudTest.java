package com.felipe.ballast.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class CategoryCrudTest {

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

  private MockHttpServletResponse send(
      Cookie session, MockHttpServletRequestBuilder request, Object body) throws Exception {
    request.cookie(session);
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private JsonNode json(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private String idOf(Cookie session, String name) throws Exception {
    for (JsonNode item : json(send(session, get("/categorias"), null))) {
      if (item.get("nome").asString().equals(name)) {
        return item.get("id").asString();
      }
    }
    throw new IllegalStateException(name);
  }

  private MockHttpServletResponse create(Cookie session, String name, String type)
      throws Exception {
    return send(session, post("/categorias"), Map.of("nome", name, "tipo", type));
  }

  @Test
  void createsRenamesAndDeletesACategory() throws Exception {
    MockHttpServletResponse created = create(sessionA, "Pets", "despesa");
    assertThat(created.getStatus()).isEqualTo(201);
    String id = json(created).get("id").asString();
    assertThat(json(created).get("protegida").asBoolean()).isFalse();

    MockHttpServletResponse renamed =
        send(sessionA, put("/categorias/" + id), Map.of("nome", "Bichos"));
    assertThat(renamed.getStatus()).isEqualTo(200);
    assertThat(json(renamed).get("nome").asString()).isEqualTo("Bichos");

    assertThat(send(sessionA, delete("/categorias/" + id), null).getStatus()).isEqualTo(204);
    assertThat(send(sessionA, delete("/categorias/" + id), null).getStatus()).isEqualTo(404);
  }

  @Test
  void refusesADuplicateNameIgnoringCase() throws Exception {
    assertThat(create(sessionA, "Pets", "despesa").getStatus()).isEqualTo(201);
    assertThat(create(sessionA, "pets", "despesa").getStatus()).isEqualTo(409);
  }

  @Test
  void protectsOutrosAndOutrasReceitas() throws Exception {
    for (String name : new String[] {"Outros", "Outras receitas"}) {
      String id = idOf(sessionA, name);
      assertThat(send(sessionA, put("/categorias/" + id), Map.of("nome", "X")).getStatus())
          .isEqualTo(409);
      assertThat(send(sessionA, delete("/categorias/" + id), null).getStatus()).isEqualTo(409);
    }
  }

  @Test
  void stopsAtTwentyCategoriesNotCountingTheProtectedOnes() throws Exception {
    // 14 sementes comuns, então cabem mais 6
    for (int i = 0; i < 6; i++) {
      assertThat(create(sessionA, "Extra " + i, "despesa").getStatus()).isEqualTo(201);
    }
    assertThat(create(sessionA, "Extra 7", "despesa").getStatus()).isEqualTo(409);
    // apagar abre vaga
    assertThat(
            send(sessionA, delete("/categorias/" + idOf(sessionA, "Extra 0")), null).getStatus())
        .isEqualTo(204);
    assertThat(create(sessionA, "Extra 8", "receita").getStatus()).isEqualTo(201);
  }

  @Test
  void anotherUsersCategoryIsNotFound() throws Exception {
    String id = idOf(sessionB, "Mercado");
    assertThat(send(sessionA, put("/categorias/" + id), Map.of("nome", "X")).getStatus())
        .isEqualTo(404);
    assertThat(send(sessionA, delete("/categorias/" + id), null).getStatus()).isEqualTo(404);
    assertThat(idOf(sessionB, "Mercado")).isEqualTo(id);
  }

  @Test
  void rejectsBadInput() throws Exception {
    assertThat(create(sessionA, "   ", "despesa").getStatus()).isEqualTo(400);
    assertThat(create(sessionA, "x".repeat(101), "despesa").getStatus()).isEqualTo(400);
    assertThat(create(sessionA, "Ok", "transferencia").getStatus()).isEqualTo(400);
    assertThat(
            send(sessionA, post("/categorias"), Map.of("nome", "Ok", "tipo", "despesa", "x", 1))
                .getStatus())
        .isEqualTo(400);
  }
}
