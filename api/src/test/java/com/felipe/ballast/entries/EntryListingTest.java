package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.expenseBody;
import static com.felipe.ballast.entries.EntriesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

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
class EntryListingTest {

  @Autowired MockMvc mvc;
  private EntriesApi api;
  private EntriesApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    actor = api.newActor();
  }

  private String create(String title, String date) throws Exception {
    return api.createOk(
            actor.session(), with(with(expenseBody(actor), "titulo", title), "data", date))
        .get("id")
        .asString();
  }

  private List<String> titles(String query) throws Exception {
    MockHttpServletResponse response = api.list(actor.session(), query);
    assertThat(response.getStatus()).as(query).isEqualTo(200);
    List<String> titles = new ArrayList<>();
    for (JsonNode item : EntriesApi.body(response)) {
      titles.add(item.get("titulo").asString());
    }
    return titles;
  }

  @Test
  void anEmptyPeriodIsAnEmptyList() throws Exception {
    MockHttpServletResponse response = api.list(actor.session(), "?de=2026-01-01&ate=2026-01-31");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(EntriesApi.text(response)).isEqualTo("[]");
  }

  @Test
  void includesBothEndsOfThePeriodAndLeavesOutTheRest() throws Exception {
    create("antes", "2026-02-28");
    create("primeiro dia", "2026-03-01");
    create("meio", "2026-03-15");
    create("último dia", "2026-03-31");
    create("depois", "2026-04-01");

    assertThat(titles("?de=2026-03-01&ate=2026-03-31"))
        .containsExactly("último dia", "meio", "primeiro dia");
  }

  @Test
  void aSingleDayPeriodWorks() throws Exception {
    create("do dia", "2026-03-10");
    create("outro dia", "2026-03-11");

    assertThat(titles("?de=2026-03-10&ate=2026-03-10")).containsExactly("do dia");
  }

  @Test
  void ordersByCashDateThenNewestCreatedFirst() throws Exception {
    create("antigo", "2026-03-01");
    create("mesmo dia, criado primeiro", "2026-03-05");
    create("mesmo dia, criado depois", "2026-03-05");
    create("recente", "2026-03-20");

    assertThat(titles("?de=2026-03-01&ate=2026-03-31"))
        .containsExactly(
            "recente", "mesmo dia, criado depois", "mesmo dia, criado primeiro", "antigo");
  }

  @Test
  void filtersByAccount() throws Exception {
    String other = api.newAccount(actor.session(), "Segunda conta");
    create("na conta 1", "2026-03-10");
    api.createOk(
        actor.session(), with(with(expenseBody(actor), "titulo", "na conta 2"), "contaId", other));

    assertThat(titles("?de=2026-03-01&ate=2026-03-31&contaId=" + other))
        .containsExactly("na conta 2");
    assertThat(titles("?de=2026-03-01&ate=2026-03-31&contaId=" + actor.accountId()))
        .containsExactly("na conta 1");
    assertThat(titles("?de=2026-03-01&ate=2026-03-31")).hasSize(2);
  }

  @Test
  void aWellFormedUnknownAccountFilterIsAnEmptyList() throws Exception {
    create("existe", "2026-03-10");

    assertThat(titles("?de=2026-03-01&ate=2026-03-31&contaId=" + EntriesApi.randomId())).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "1-1-1-1-1", "", "null"})
  void aMalformedAccountFilterIs400(String accountId) throws Exception {
    MockHttpServletResponse response =
        api.list(actor.session(), "?de=2026-03-01&ate=2026-03-31&contaId=" + accountId);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"contaId deve ser um UUID\"}");
  }

  @Test
  void accepts366DaysAndRejects367() throws Exception {
    assertThat(api.list(actor.session(), "?de=2028-01-01&ate=2028-12-31").getStatus())
        .isEqualTo(200);

    MockHttpServletResponse tooLong = api.list(actor.session(), "?de=2028-01-01&ate=2029-01-01");

    assertThat(tooLong.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(tooLong))
        .isEqualTo("{\"erro\":\"o intervalo entre de e ate deve ter no máximo 366 dias\"}");
  }

  @Test
  void aStartAfterTheEndIs400() throws Exception {
    MockHttpServletResponse response = api.list(actor.session(), "?de=2026-03-02&ate=2026-03-01");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response))
        .isEqualTo("{\"erro\":\"de deve ser anterior ou igual a ate\"}");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "?ate=2026-03-31",
        "?de=2026-03-01",
        "?de=&ate=2026-03-31",
        "?de=2026-02-30&ate=2026-03-31",
        "?de=2026-3-1&ate=2026-03-31",
        "?de=20260301&ate=2026-03-31",
        "?de=2026-03-01&ate=2026-03-32",
        "?de=2026-03-01&ate=",
        "?de=1899-12-31&ate=1900-01-02",
        "?de=2199-12-31&ate=2200-01-01",
        "?de=2026-03-01&de=2026-03-02&ate=2026-03-31"
      })
  void aMissingOrInvalidDateIs400(String query) throws Exception {
    MockHttpServletResponse response = api.list(actor.session(), query);

    assertThat(response.getStatus()).as(query).isEqualTo(400);
  }
}
