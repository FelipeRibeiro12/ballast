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

@IntegrationTest
class EntryUpdateDeleteTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private EntriesApi api;
  private EntriesApi.Actor actor;
  private JsonNode created;
  private String entryId;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    actor = api.newActor();
    created = api.createOk(actor.session(), expenseBody(actor));
    entryId = created.get("id").asString();
  }

  @Test
  void putReplacesEveryFieldAndKeepsIdAndCreationTime() throws Exception {
    Map<String, Object> body = incomeBody(actor);
    body = with(body, "titulo", "Bônus");
    body = with(body, "descricao", "pago em atraso");
    body = with(body, "valorCentavos", 50_000);
    body = with(body, "data", "2026-04-02");

    MockHttpServletResponse response = api.update(actor.session(), entryId, body);

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode updated = EntriesApi.body(response);
    assertThat(updated.get("id").asString()).isEqualTo(entryId);
    assertThat(updated.get("criadoEm")).isEqualTo(created.get("criadoEm"));
    assertThat(updated.get("tipo").asString()).isEqualTo("receita");
    assertThat(updated.get("titulo").asString()).isEqualTo("Bônus");
    assertThat(updated.get("descricao").asString()).isEqualTo("pago em atraso");
    assertThat(updated.get("valorCentavos").asLong()).isEqualTo(50_000L);
    assertThat(updated.get("categoriaId").asString()).isEqualTo(actor.incomeCategoryId());
    assertThat(updated.get("dataCompetencia").asString()).isEqualTo("2026-04-02");
    assertThat(updated.get("dataCaixa").asString()).isEqualTo("2026-04-02");
    assertThat(EntriesApi.body(api.find(actor.session(), entryId))).isEqualTo(updated);
  }

  @Test
  void putWritesTheNewDateInBothColumns() throws Exception {
    api.update(actor.session(), entryId, with(expenseBody(actor), "data", "2026-05-20"));

    Map<String, Object> row =
        jdbc.sql("SELECT data_competencia, data_caixa FROM lancamento WHERE id = :id")
            .param("id", UUID.fromString(entryId))
            .query()
            .singleRow();
    assertThat(row.get("data_competencia").toString()).isEqualTo("2026-05-20");
    assertThat(row.get("data_caixa").toString()).isEqualTo("2026-05-20");
  }

  @Test
  void putClearsTheDescriptionWhenItComesBlank() throws Exception {
    api.update(actor.session(), entryId, with(expenseBody(actor), "descricao", "algo"));

    JsonNode updated =
        EntriesApi.body(
            api.update(actor.session(), entryId, with(expenseBody(actor), "descricao", "  ")));

    assertThat(updated.get("descricao").isNull()).isTrue();
  }

  @Test
  void putWithInvalidInputIs400AndChangesNothing() throws Exception {
    MockHttpServletResponse response =
        api.update(actor.session(), entryId, with(expenseBody(actor), "valorCentavos", 0));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.body(api.find(actor.session(), entryId))).isEqualTo(created);
  }

  @Test
  void putWithAnUndeclaredFieldIs400() throws Exception {
    MockHttpServletResponse response =
        api.update(actor.session(), entryId, with(expenseBody(actor), "dataCaixa", "2026-01-01"));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.body(api.find(actor.session(), entryId))).isEqualTo(created);
  }

  @Test
  void putOfAnUnknownIdIs404() throws Exception {
    MockHttpServletResponse response =
        api.update(actor.session(), EntriesApi.randomId(), expenseBody(actor));

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"não encontrado\"}");
  }

  @Test
  void putChangingTheCategoryToTheWrongTypeIs400() throws Exception {
    MockHttpServletResponse response =
        api.update(
            actor.session(),
            entryId,
            with(expenseBody(actor), "categoriaId", actor.incomeCategoryId()));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"categoria não encontrada\"}");
  }

  @Test
  void movingToAnotherAccountMovesTheBalanceOfBoth() throws Exception {
    String second = api.newAccount(actor.session(), "Segunda");
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-12_345L);
    assertThat(api.accountBalance(actor.session(), second)).isZero();

    MockHttpServletResponse response =
        api.update(actor.session(), entryId, with(expenseBody(actor), "contaId", second));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isZero();
    assertThat(api.accountBalance(actor.session(), second)).isEqualTo(-12_345L);
  }

  @Test
  void changingTypeAndAmountReactsInTheBalance() throws Exception {
    api.update(actor.session(), entryId, with(incomeBody(actor), "valorCentavos", 700));

    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(700L);
  }

  @Test
  void movingToAnArchivedAccountIs400() throws Exception {
    String archived = api.newAccount(actor.session(), "Arquivada");
    api.setAccountArchived(actor.session(), archived, true);

    MockHttpServletResponse response =
        api.update(actor.session(), entryId, with(expenseBody(actor), "contaId", archived));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"conta arquivada\"}");
    assertThat(EntriesApi.body(api.find(actor.session(), entryId))).isEqualTo(created);
  }

  @Test
  void editingAnEntryThatStaysInAnArchivedAccountIsAllowed() throws Exception {
    api.setAccountArchived(actor.session(), actor.accountId(), true);

    MockHttpServletResponse response =
        api.update(actor.session(), entryId, with(expenseBody(actor), "titulo", "Corrigido"));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(EntriesApi.body(response).get("titulo").asString()).isEqualTo("Corrigido");
  }

  @Test
  void deleteAnswers204RemovesTheEntryAndTheBalanceForgetsIt() throws Exception {
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-12_345L);

    MockHttpServletResponse response = api.remove(actor.session(), entryId);

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(response.getContentAsByteArray()).isEmpty();
    assertThat(api.find(actor.session(), entryId).getStatus()).isEqualTo(404);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isZero();
    assertThat(
            jdbc.sql("SELECT count(*) FROM lancamento WHERE id = :id")
                .param("id", UUID.fromString(entryId))
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void deletingTwiceIs404TheSecondTime() throws Exception {
    api.remove(actor.session(), entryId);

    assertThat(api.remove(actor.session(), entryId).getStatus()).isEqualTo(404);
  }

  @Test
  void deletingAnEntryOfAnArchivedAccountIsAllowed() throws Exception {
    api.setAccountArchived(actor.session(), actor.accountId(), true);

    assertThat(api.remove(actor.session(), entryId).getStatus()).isEqualTo(204);
  }
}
