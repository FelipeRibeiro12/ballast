package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.expenseBody;
import static com.felipe.ballast.entries.EntriesApi.incomeBody;
import static com.felipe.ballast.entries.EntriesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// O saldo de cada conta fica em ±9007199254740991 centavos (ADR-024): o lançamento que o levaria
// para fora da faixa é recusado e nada grava.
@IntegrationTest
class EntryBalanceLimitTest {

  private static final long CEILING = 9_007_199_254_740_991L;
  private static final String LIMIT_ERROR = "{\"erro\":\"saldo da conta fora do limite\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private EntriesApi api;
  private EntriesApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    actor = api.newActor();
  }

  private Map<String, Object> income(String accountId, long cents) {
    return with(with(incomeBody(actor), "contaId", accountId), "valorCentavos", cents);
  }

  private Map<String, Object> expense(String accountId, long cents) {
    return with(with(expenseBody(actor), "contaId", accountId), "valorCentavos", cents);
  }

  private long countEntries(String accountId) {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE conta_id = :id")
        .param("id", UUID.fromString(accountId))
        .query(Long.class)
        .single();
  }

  private void assertLimitError(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo(LIMIT_ERROR);
  }

  @Test
  void secondIncomeAtTheCeilingIsRejectedAndTheBalanceStaysAtTheCeiling() throws Exception {
    api.createOk(actor.session(), income(actor.accountId(), CEILING));

    MockHttpServletResponse second =
        api.create(actor.session(), income(actor.accountId(), CEILING));

    assertLimitError(second);
    assertThat(countEntries(actor.accountId())).isEqualTo(1);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(CEILING);
  }

  @Test
  void anIncomeThatLandsExactlyOnTheCeilingIsAccepted() throws Exception {
    api.createOk(actor.session(), income(actor.accountId(), CEILING - 1));

    assertThat(api.create(actor.session(), income(actor.accountId(), 1)).getStatus())
        .isEqualTo(201);
    assertThat(api.create(actor.session(), income(actor.accountId(), 1)).getStatus())
        .isEqualTo(400);
  }

  @Test
  void anExpenseThatTakesTheBalanceBelowTheFloorIsRejected() throws Exception {
    api.createOk(actor.session(), expense(actor.accountId(), CEILING));

    MockHttpServletResponse second = api.create(actor.session(), expense(actor.accountId(), 1));

    assertLimitError(second);
    assertThat(countEntries(actor.accountId())).isEqualTo(1);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-CEILING);
  }

  @Test
  void putMovingAnEntryToAnAccountThatWouldOverflowIsRejectedAndChangesNothing() throws Exception {
    String other = api.newAccount(actor.session(), "Destino " + UUID.randomUUID());
    api.createOk(actor.session(), income(other, CEILING));
    JsonNode small = api.createOk(actor.session(), income(actor.accountId(), 1));
    String id = small.get("id").asString();

    MockHttpServletResponse response = api.update(actor.session(), id, income(other, 1));

    assertLimitError(response);
    assertThat(EntriesApi.body(api.find(actor.session(), id))).isEqualTo(small);
    assertThat(api.accountBalance(actor.session(), other)).isEqualTo(CEILING);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(1);
  }

  @Test
  void putRaisingTheValueInTheSameAccountPastTheCeilingIsRejected() throws Exception {
    api.createOk(actor.session(), income(actor.accountId(), CEILING - 10));
    JsonNode small = api.createOk(actor.session(), income(actor.accountId(), 5));
    String id = small.get("id").asString();

    assertLimitError(api.update(actor.session(), id, income(actor.accountId(), 11)));
    assertThat(api.update(actor.session(), id, income(actor.accountId(), 10)).getStatus())
        .isEqualTo(200);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(CEILING);
  }

  @Test
  void deleteThatWouldTakeTheBalanceOutOfRangeIsRejectedAndTheEntryStays() throws Exception {
    JsonNode bigIncome = api.createOk(actor.session(), income(actor.accountId(), CEILING));
    api.createOk(actor.session(), expense(actor.accountId(), CEILING));
    api.createOk(actor.session(), expense(actor.accountId(), CEILING));
    String id = bigIncome.get("id").asString();

    MockHttpServletResponse response = api.remove(actor.session(), id);

    assertLimitError(response);
    assertThat(api.find(actor.session(), id).getStatus()).isEqualTo(200);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-CEILING);
  }

  @Test
  void deleteThatKeepsTheBalanceInRangeStillWorks() throws Exception {
    JsonNode entry = api.createOk(actor.session(), income(actor.accountId(), CEILING));

    assertThat(api.remove(actor.session(), entry.get("id").asString()).getStatus()).isEqualTo(204);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isZero();
  }
}
