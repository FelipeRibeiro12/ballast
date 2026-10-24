package com.felipe.ballast.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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

// SameSite=Lax já barra o cookie em POST de outro site, mas DELETE sem corpo e sem preflight
// depende só disso. Rota que muda estado confere o Origin (ou o Sec-Fetch-Site, sem Origin).
@IntegrationTest
class OriginCheckTest {

  private static final String FORBIDDEN = "{\"erro\":\"origem não permitida\"}";

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

  // O MockMvc não manda Host; um navegador sempre manda.
  private static MockHttpServletRequestBuilder deleteAccount() {
    return delete("/contas/" + UUID.randomUUID()).header("Host", "localhost");
  }

  @Test
  void deleteFromAnotherOriginIsForbidden() throws Exception {
    MockHttpServletResponse response = send(deleteAccount().header("Origin", "https://evil.test"));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).isEqualTo(FORBIDDEN);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
  }

  @Test
  void everyStateChangingMethodChecksTheOrigin() throws Exception {
    String evil = "https://evil.test";
    String json = "{}";
    assertThat(
            send(post("/contas")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json)
                    .header("Origin", evil))
                .getStatus())
        .isEqualTo(403);
    assertThat(
            send(put("/lancamentos/" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json)
                    .header("Origin", evil))
                .getStatus())
        .isEqualTo(403);
    assertThat(
            send(patch("/contas/" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json)
                    .header("Origin", evil))
                .getStatus())
        .isEqualTo(403);
    assertThat(
            send(delete("/recorrencias/" + UUID.randomUUID()).header("Origin", evil)).getStatus())
        .isEqualTo(403);
    assertThat(send(post("/auth/logout").header("Origin", evil)).getStatus()).isEqualTo(403);
  }

  @Test
  void loginFromAnotherOriginIsForbiddenBeforeAnyPasswordCheck() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/auth/login")
                    .header("Origin", "https://evil.test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"a@exemplo.test\",\"senha\":\"x\"}"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(403);
  }

  @ParameterizedTest(name = "Origin {0}")
  @ValueSource(
      strings = {
        "https://app.exemplo.test",
        "http://localhost:5173",
        // Mesma origem do pedido: Host localhost, qualquer esquema.
        "http://localhost",
        "https://localhost"
      })
  void listedOriginAndSameHostPass(String origin) throws Exception {
    // Conta inexistente: 404 prova que o pedido passou do filtro.
    assertThat(send(deleteAccount().header("Origin", origin)).getStatus()).isEqualTo(404);
  }

  @ParameterizedTest(name = "Origin {0}")
  @ValueSource(
      strings = {
        "null",
        "",
        "https://app.exemplo.test.evil.test",
        "https://evil.test/https://app.exemplo.test",
        "http://localhost:6666",
        "app.exemplo.test"
      })
  void nullEmptyAndLookalikeOriginsAreForbidden(String origin) throws Exception {
    assertThat(send(deleteAccount().header("Origin", origin)).getStatus()).isEqualTo(403);
  }

  @Test
  void withoutOriginAndWithoutFetchMetadataItPasses() throws Exception {
    // curl, testes e clientes que não são navegador.
    assertThat(send(deleteAccount()).getStatus()).isEqualTo(404);
  }

  @ParameterizedTest(name = "Sec-Fetch-Site {0}")
  @ValueSource(strings = {"same-origin", "same-site", "none"})
  void withoutOriginANonCrossSiteFetchPasses(String site) throws Exception {
    assertThat(send(deleteAccount().header("Sec-Fetch-Site", site)).getStatus()).isEqualTo(404);
  }

  @Test
  void withoutOriginACrossSiteFetchIsForbidden() throws Exception {
    MockHttpServletResponse response = send(deleteAccount().header("Sec-Fetch-Site", "cross-site"));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).isEqualTo(FORBIDDEN);
  }

  @Test
  void aListedOriginWinsOverCrossSiteMetadata() throws Exception {
    // Com Origin presente é ele que decide; o Sec-Fetch-Site só vale na falta dele.
    assertThat(
            send(deleteAccount()
                    .header("Origin", "https://app.exemplo.test")
                    .header("Sec-Fetch-Site", "cross-site"))
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  void safeMethodsDoNotCheckTheOrigin() throws Exception {
    assertThat(send(get("/contas").header("Origin", "https://evil.test")).getStatus())
        .isEqualTo(200);
  }
}
