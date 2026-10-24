package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.expenseBody;
import static com.felipe.ballast.entries.EntriesApi.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.felipe.ballast.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class EntryPaginationTest {

  private static final String PERIOD = "de=2026-03-01&ate=2026-03-31";
  private static final String NEXT = "X-Next-Cursor";

  @Autowired MockMvc mvc;
  private EntriesApi api;
  private EntriesApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    actor = api.newActor();
  }

  private void create(String title, String date) throws Exception {
    api.createOk(actor.session(), with(with(expenseBody(actor), "titulo", title), "data", date));
  }

  private static List<String> titles(MockHttpServletResponse response) throws Exception {
    List<String> titles = new ArrayList<>();
    for (JsonNode item : EntriesApi.body(response)) {
      titles.add(item.get("titulo").asString());
    }
    return titles;
  }

  @Test
  void withoutParametersReturnsEverythingAndNoCursor() throws Exception {
    create("a", "2026-03-01");
    create("b", "2026-03-02");

    MockHttpServletResponse response = api.list(actor.session(), "?" + PERIOD);

    assertThat(titles(response)).containsExactly("b", "a");
    assertThat(response.getHeader(NEXT)).isNull();
  }

  @Test
  void walkingTheCursorsGivesTheSameListAsOneBigPage() throws Exception {
    create("a", "2026-03-01");
    create("b", "2026-03-05");
    create("c", "2026-03-05");
    create("d", "2026-03-05");
    create("e", "2026-03-20");
    List<String> whole = titles(api.list(actor.session(), "?" + PERIOD));

    List<String> walked = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      String query = "?" + PERIOD + "&limite=2" + (cursor == null ? "" : "&cursor=" + cursor);
      MockHttpServletResponse page = api.list(actor.session(), query);
      assertThat(page.getStatus()).isEqualTo(200);
      walked.addAll(titles(page));
      cursor = page.getHeader(NEXT);
      pages++;
    } while (cursor != null);

    assertThat(walked).isEqualTo(whole).hasSize(5);
    assertThat(pages).isEqualTo(3);
  }

  @Test
  void aPageThatEndsExactlyAtTheLastItemHasNoCursor() throws Exception {
    create("a", "2026-03-01");
    create("b", "2026-03-02");

    MockHttpServletResponse response = api.list(actor.session(), "?" + PERIOD + "&limite=2");

    assertThat(titles(response)).hasSize(2);
    assertThat(response.getHeader(NEXT)).isNull();
  }

  @Test
  void theCursorKeepsTheAccountFilter() throws Exception {
    String other = api.newAccount(actor.session(), "Outra " + EntriesApi.randomId());
    create("a", "2026-03-01");
    create("b", "2026-03-02");
    api.createOk(
        actor.session(), with(with(expenseBody(actor), "contaId", other), "data", "2026-03-03"));

    MockHttpServletResponse first =
        api.list(actor.session(), "?" + PERIOD + "&contaId=" + actor.accountId() + "&limite=1");
    MockHttpServletResponse second =
        api.list(
            actor.session(),
            "?"
                + PERIOD
                + "&contaId="
                + actor.accountId()
                + "&limite=1&cursor="
                + first.getHeader(NEXT));

    assertThat(titles(first)).containsExactly("b");
    assertThat(titles(second)).containsExactly("a");
    assertThat(second.getHeader(NEXT)).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "1001", "abc", "", "1.5", "99999999999999999999"})
  void rejectsALimitOutsideTheRange(String limit) throws Exception {
    MockHttpServletResponse response = api.list(actor.session(), "?" + PERIOD + "&limite=" + limit);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response))
        .isEqualTo("{\"erro\":\"limite deve ser um inteiro de 1 a 1000\"}");
  }

  @ParameterizedTest
  @ValueSource(strings = {"lixo", "!!!", "YWJj", "", "eA"})
  void rejectsAnInvalidCursor(String cursor) throws Exception {
    MockHttpServletResponse response =
        api.list(actor.session(), "?" + PERIOD + "&cursor=" + cursor);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"cursor inválido\"}");
  }

  @Test
  void aCursorFromAnotherUserOnlyMovesWithinTheCallersOwnData() throws Exception {
    create("do A 1", "2026-03-01");
    create("do A 2", "2026-03-02");
    String cursorOfA = api.list(actor.session(), "?" + PERIOD + "&limite=1").getHeader(NEXT);
    EntriesApi.Actor stranger = api.newActor();
    api.createOk(
        stranger.session(),
        with(with(expenseBody(stranger), "titulo", "do B"), "data", "2026-03-01"));

    MockHttpServletResponse response =
        api.list(stranger.session(), "?" + PERIOD + "&cursor=" + cursorOfA);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(titles(response)).containsExactly("do B");
  }

  @Test
  void theNewParametersAreDeclaredSoOthersStillGive400() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(get("/lancamentos?" + PERIOD + "&limit=2").cookie(actor.session()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
  }
}
