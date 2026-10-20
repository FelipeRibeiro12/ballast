package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// A olha para o que é de B pela API: criação com recurso alheio. Leitura e desativação de
// recorrência alheia estão em RecurrenceReadAndDeactivateTest.
@IntegrationTest
class RecurrenceAuthorizationTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private RecurrencesApi api;
  private RecurrenceTestSupport db;
  private RecurrencesApi.Actor a;
  private RecurrencesApi.Actor b;

  @BeforeEach
  void setUp() throws Exception {
    api = new RecurrencesApi(mvc);
    db = new RecurrenceTestSupport(jdbc);
    a = api.newActor();
    b = api.newActor();
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  @Test
  void aCannotCreateWithBsAccount() throws Exception {
    MockHttpServletResponse response =
        api.create(a.session(), with(monthlyBody(a), "contaId", b.accountId()));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response)).isEqualTo("{\"erro\":\"conta não encontrada\"}");
    assertNothingWritten();
  }

  @Test
  void aCannotCreateWithBsCategory() throws Exception {
    MockHttpServletResponse response =
        api.create(a.session(), with(monthlyBody(a), "categoriaId", b.expenseCategoryId()));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response)).isEqualTo("{\"erro\":\"categoria não encontrada\"}");
    assertNothingWritten();
  }

  @Test
  void bsRecurrenceNeverAppearsInAsList() throws Exception {
    api.createOk(b.session(), monthlyBody(b));

    assertThat(RecurrencesApi.text(api.list(a.session()))).isEqualTo("[]");
    assertThat(RecurrencesApi.body(api.listEntries(a.session(), "2026-10-01", "2027-09-30")))
        .isEmpty();
  }

  private void assertNothingWritten() {
    assertThat(db.countTemplates(a.user().id())).isZero();
    assertThat(db.countRules(a.user().id())).isZero();
    assertThat(db.countEntries(a.user().id())).isZero();
    assertThat(db.countEntries(b.user().id())).isZero();
  }
}
