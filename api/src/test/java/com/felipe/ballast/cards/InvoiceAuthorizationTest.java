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
class InvoiceAuthorizationTest {

  private static final String QUERY = "?de=2026-03-01&ate=2026-03-31";
  private static final String NOT_FOUND = "{\"erro\":\"não encontrado\"}";

  @Autowired MockMvc mvc;
  private InvoicesApi api;
  private InvoicesApi.Actor userA;
  private InvoicesApi.Actor userB;

  @BeforeEach
  void setUp() throws Exception {
    api = new InvoicesApi(mvc);
    userA = api.newActor(15, 25);
    userB = api.newActor(15, 25);
  }

  private static String message(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void aCardOfAnotherUserLooksLikeAnUnknownId() throws Exception {
    MockHttpServletResponse foreign = api.invoices(userA.session(), userB.cardId(), QUERY);
    MockHttpServletResponse unknown =
        api.invoices(userA.session(), InvoicesApi.newUnknownId(), QUERY);

    assertThat(foreign.getStatus()).isEqualTo(404);
    assertThat(message(foreign)).isEqualTo(NOT_FOUND);
    assertThat(unknown.getStatus()).isEqualTo(404);
    assertThat(message(unknown)).isEqualTo(message(foreign));
  }

  @Test
  void theUsersOwnCheckingAccountIsNotACard() throws Exception {
    MockHttpServletResponse response = api.invoices(userA.session(), userA.checkingId(), QUERY);

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(message(response)).isEqualTo(NOT_FOUND);
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000G"})
  void aMalformedIdGivesTheSame404(String id) throws Exception {
    MockHttpServletResponse response = api.invoices(userA.session(), id, QUERY);

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(message(response)).isEqualTo(NOT_FOUND);
  }

  @Test
  void anUppercaseCanonicalIdOfTheOwnCardIsAccepted() throws Exception {
    MockHttpServletResponse response =
        api.invoices(userA.session(), userA.cardId().toUpperCase(), QUERY);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void withoutASessionTheAnswerIs401() throws Exception {
    MockHttpServletResponse response = api.invoices(null, userA.cardId(), QUERY);

    assertThat(response.getStatus()).isEqualTo(401);
  }
}
