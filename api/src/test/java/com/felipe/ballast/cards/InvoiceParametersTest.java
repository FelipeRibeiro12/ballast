package com.felipe.ballast.cards;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class InvoiceParametersTest {

  @Autowired MockMvc mvc;
  private InvoicesApi api;
  private InvoicesApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new InvoicesApi(mvc);
    actor = api.newActor(15, 25);
  }

  private MockHttpServletResponse call(String query) throws Exception {
    return api.invoices(actor.session(), actor.cardId(), query);
  }

  private static String message(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void accepts366DaysCountingBothEnds() throws Exception {
    assertThat(call("?de=2028-01-01&ate=2028-12-31").getStatus()).isEqualTo(200);
  }

  @Test
  void refuses367Days() throws Exception {
    MockHttpServletResponse response = call("?de=2028-01-01&ate=2029-01-01");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(message(response))
        .isEqualTo("{\"erro\":\"o intervalo entre de e ate deve ter no máximo 366 dias\"}");
  }

  @Test
  void refusesFromAfterTo() throws Exception {
    MockHttpServletResponse response = call("?de=2026-03-02&ate=2026-03-01");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(message(response)).isEqualTo("{\"erro\":\"de deve ser anterior ou igual a ate\"}");
  }

  @Test
  void acceptsFromEqualToTo() throws Exception {
    assertThat(call("?de=2026-03-25&ate=2026-03-25").getStatus()).isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "?ate=2026-03-31",
        "?de=&ate=2026-03-31",
        "?de=2026-3-1&ate=2026-03-31",
        "?de=2026-02-30&ate=2026-03-31",
        "?de=20260301&ate=2026-03-31",
        "?de=1899-12-31&ate=2026-03-31",
        "?de=2026-03-01,2026-03-01&ate=2026-03-31"
      })
  void refusesAMissingOrMalformedFrom(String query) throws Exception {
    MockHttpServletResponse response = call(query);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(message(response))
        .isEqualTo("{\"erro\":\"de deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199\"}");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "?de=2026-03-01",
        "?de=2026-03-01&ate=",
        "?de=2026-03-01&ate=2026-13-01",
        "?de=2026-03-01&ate=2200-01-01"
      })
  void refusesAMissingOrMalformedTo(String query) throws Exception {
    MockHttpServletResponse response = call(query);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(message(response))
        .isEqualTo("{\"erro\":\"ate deve ser uma data AAAA-MM-DD com ano entre 1900 e 2199\"}");
  }
}
