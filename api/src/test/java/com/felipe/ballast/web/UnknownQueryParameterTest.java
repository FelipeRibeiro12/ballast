package com.felipe.ballast.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// Parâmetro de query que a rota não declara é erro, não ignorado: ?contaid= (id minúsculo)
// devolveria o extrato de todas as contas e quem chamou acharia que filtrou.
@IntegrationTest
class UnknownQueryParameterTest {

  @Autowired MockMvc mvc;

  private Cookie session;

  @BeforeEach
  void logIn() throws Exception {
    TestUsers users = new TestUsers(mvc);
    session = users.session(users.register());
  }

  private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.cookie(session)).andReturn().getResponse();
  }

  @ParameterizedTest(name = "GET {0}")
  @ValueSource(
      strings = {
        "/lancamentos?de=2026-01-01&ate=2026-01-31&contaid=00000000-0000-0000-0000-000000000001",
        "/lancamentos?de=2026-01-01&ate=2026-01-31&contaId=00000000-0000-0000-0000-000000000001&x=1",
        "/contas?arquivada=true",
        "/categorias?type=receita",
        "/saldo-projetado?dia=30",
        "/relatorios?mes=2026-10&extra=1",
        "/orcamentos?month=2026-10",
        "/posicoes-cripto?foo=bar",
        "/indices?x"
      })
  void unknownParameterIsBadRequest(String target) throws Exception {
    MockHttpServletResponse response = send(get(target));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("\"erro\"");
  }

  @Test
  void routeWithoutDeclaredParametersRefusesAnyQuery() throws Exception {
    assertThat(send(get("/contas/" + UUID.randomUUID() + "?x=1")).getStatus()).isEqualTo(400);
    assertThat(send(delete("/contas/" + UUID.randomUUID() + "?x=1")).getStatus()).isEqualTo(400);
    assertThat(
            send(post("/contas?x=1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nome\":\"A\",\"tipo\":\"corrente\"}"))
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void escapedNameOfAnUnknownParameterIsRefusedToo() throws Exception {
    assertThat(send(get("/contas?arquivada%73=true&%zz=1")).getStatus()).isEqualTo(400);
  }

  @Test
  void declaredParametersStillWork() throws Exception {
    assertThat(send(get("/lancamentos?de=2026-01-01&ate=2026-01-31")).getStatus()).isEqualTo(200);
    assertThat(send(get("/contas?arquivadas=true")).getStatus()).isEqualTo(200);
    assertThat(send(get("/categorias?tipo=receita")).getStatus()).isEqualTo(200);
    assertThat(send(get("/contas")).getStatus()).isEqualTo(200);
  }

  @Test
  void unknownParameterOnARouteWithoutSessionStillAnswers401() throws Exception {
    assertThat(mvc.perform(get("/contas?x=1")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
  }
}
