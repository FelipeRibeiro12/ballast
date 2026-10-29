package com.felipe.ballast.imports;

import static com.felipe.ballast.imports.ImportsApi.body;
import static com.felipe.ballast.imports.ImportsApi.line;
import static com.felipe.ballast.imports.ImportsApi.ofx;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.imports.ImportsApi.Tx;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ImportConfirmTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private ImportsApi api;
  private ImportsApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new ImportsApi(mvc);
    actor = api.newActor();
  }

  private long entryCount() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :u")
        .param("u", actor.user().id())
        .query(Long.class)
        .single();
  }

  private static Map<String, Object> decision(String action) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("acao", action);
    return body;
  }

  private JsonNode entries(String from, String to) throws Exception {
    return body(api.listEntries(actor.session(), from, to));
  }

  @Test
  void confirmingCreatesTheEntriesWithBothDatesAndCents() throws Exception {
    JsonNode detail =
        api.uploadOk(
            actor.session(),
            actor.accountId(),
            ofx(
                new Tx("20261003", "-35.90", "F1", "PADARIA"),
                new Tx("20261005", "1500.00", "F2", "SALARIO")));
    String id = detail.get("id").asString();

    MockHttpServletResponse response = api.confirm(actor.session(), id, null);

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode result = body(response);
    assertThat(result.get("status").asString()).isEqualTo("confirmada");
    assertThat(result.get("criadas").asInt()).isEqualTo(2);
    JsonNode created = entries("2026-10-01", "2026-10-31");
    assertThat(created).hasSize(2);
    JsonNode expense = null;
    for (JsonNode entry : created) {
      if (entry.get("tipo").asString().equals("despesa")) {
        expense = entry;
      }
    }
    assertThat(expense.get("valorCentavos").asLong()).isEqualTo(3590);
    assertThat(expense.get("dataCompetencia").asString()).isEqualTo("2026-10-03");
    assertThat(expense.get("dataCaixa").asString()).isEqualTo("2026-10-03");
    assertThat(expense.get("titulo").asString()).isEqualTo("PADARIA");
    assertThat(expense.get("categoriaId").asString())
        .isEqualTo(api.categoryId(actor.session(), "Outros"));
    JsonNode after = body(api.find(actor.session(), id));
    assertThat(after.get("status").asString()).isEqualTo("confirmada");
    assertThat(line(after, 1).get("resultado").asString()).isEqualTo("criada");
    assertThat(line(after, 1).get("lancamentoId").asString())
        .isEqualTo(expense.get("id").asString());
  }

  @Test
  void aCardAccountGetsTheInvoiceDueDateAsCashDate() throws Exception {
    String card = api.newCard(actor.session(), actor.accountId(), 10, 20);
    JsonNode detail =
        api.uploadOk(
            actor.session(),
            card,
            ofx(
                new Tx("20261003", "-50.00", "C1", "ANTES DO FECHAMENTO"),
                new Tx("20261010", "-60.00", "C2", "NO DIA DO FECHAMENTO"),
                new Tx("20261015", "-70.00", "C3", "DEPOIS DO FECHAMENTO")));
    assertThat(api.confirm(actor.session(), detail.get("id").asString(), null).getStatus())
        .isEqualTo(200);
    // Os mesmos dias lançados à mão mostram o que a regra do cartão dá para cada um.
    api.createEntry(
        actor.session(), "despesa", 1111, "2026-10-03", card, actor.expenseCategoryId());
    api.createEntry(
        actor.session(), "despesa", 2222, "2026-10-10", card, actor.expenseCategoryId());
    api.createEntry(
        actor.session(), "despesa", 3333, "2026-10-15", card, actor.expenseCategoryId());

    Map<String, String> imported = new LinkedHashMap<>();
    Map<String, String> manual = new LinkedHashMap<>();
    for (JsonNode entry : entries("2026-10-01", "2026-12-31")) {
      long cents = entry.get("valorCentavos").asLong();
      String competence = entry.get("dataCompetencia").asString();
      if (cents < 1000) {
        continue;
      }
      (cents >= 5000 && cents <= 7000 ? imported : manual)
          .put(competence, entry.get("dataCaixa").asString());
    }

    assertThat(imported).hasSize(3).containsKeys("2026-10-03", "2026-10-10", "2026-10-15");
    assertThat(imported).isEqualTo(manual);
    assertThat(imported.get("2026-10-15")).isNotEqualTo("2026-10-15");
  }

  @Test
  void confirmingTwiceIsAConflict() throws Exception {
    String id =
        api.uploadOk(
                actor.session(),
                actor.accountId(),
                ofx(new Tx("20261003", "-35.90", "F1", "PADARIA")))
            .get("id")
            .asString();
    assertThat(api.confirm(actor.session(), id, null).getStatus()).isEqualTo(200);

    assertThat(api.confirm(actor.session(), id, null).getStatus()).isEqualTo(409);
    assertThat(entryCount()).isEqualTo(1);
  }

  @Test
  void aConfirmedImportCannotBeDiscardedOrEdited() throws Exception {
    JsonNode detail =
        api.uploadOk(
            actor.session(), actor.accountId(), ofx(new Tx("20261003", "-35.90", "F1", "PADARIA")));
    String id = detail.get("id").asString();
    api.confirm(actor.session(), id, null);

    assertThat(api.discard(actor.session(), id).getStatus()).isEqualTo(409);
    assertThat(
            api.decide(
                    actor.session(), id, line(detail, 1).get("id").asString(), decision("ignorar"))
                .getStatus())
        .isEqualTo(409);
  }

  @Test
  void theSameIdempotencyKeyRepeatsTheResponseWithoutCreatingAgain() throws Exception {
    String id =
        api.uploadOk(
                actor.session(),
                actor.accountId(),
                ofx(new Tx("20261003", "-35.90", "F1", "PADARIA")))
            .get("id")
            .asString();

    MockHttpServletResponse first = api.confirm(actor.session(), id, "chave-importacao-1");
    MockHttpServletResponse second = api.confirm(actor.session(), id, "chave-importacao-1");

    assertThat(first.getStatus()).isEqualTo(200);
    assertThat(second.getStatus()).isEqualTo(200);
    assertThat(second.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(body(second)).isEqualTo(body(first));
    assertThat(entryCount()).isEqualTo(1);
  }

  @Test
  void twoSimultaneousConfirmationsCreateOnce() throws Exception {
    Tx[] lines = new Tx[10];
    for (int i = 0; i < lines.length; i++) {
      lines[i] = new Tx("20261003", "-" + (i + 1) + ".00", "F" + i, "ITEM " + i);
    }
    String id = api.uploadOk(actor.session(), actor.accountId(), ofx(lines)).get("id").asString();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        Callable<Integer> call =
            () -> {
              barrier.await();
              return api.confirm(actor.session(), id, null).getStatus();
            };
        results.add(pool.submit(call));
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get());
      }

      assertThat(statuses).containsOnlyOnce(200);
      assertThat(statuses).allMatch(status -> status == 200 || status == 409);
      assertThat(entryCount()).isEqualTo(10);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void overlappingStatementsDoNotDuplicateEntries() throws Exception {
    String first =
        api.uploadOk(
                actor.session(),
                actor.accountId(),
                ofx(
                    new Tx("20261001", "-10.00", "A", "UM"),
                    new Tx("20261002", "-20.00", "B", "DOIS")))
            .get("id")
            .asString();
    api.confirm(actor.session(), first, null);

    JsonNode second =
        api.uploadOk(
            actor.session(),
            actor.accountId(),
            ofx(
                new Tx("20261002", "-20.00", "B", "DOIS"),
                new Tx("20261003", "-30.00", "C", "TRES")));

    assertThat(line(second, 1).get("proposta").asString()).isEqualTo("ignorar");
    assertThat(line(second, 1).get("motivo").asString()).isEqualTo("ja_importada");
    assertThat(line(second, 2).get("proposta").asString()).isEqualTo("importar");
    api.confirm(actor.session(), second.get("id").asString(), null);
    assertThat(entryCount()).isEqualTo(3);
  }

  @Test
  void aHashThatAppearedMeanwhileBecomesDuplicatedWithoutError() throws Exception {
    JsonNode one =
        api.uploadOk(
            actor.session(),
            actor.accountId(),
            ofx(
                new Tx("20261002", "-20.00", "B", "DOIS"),
                new Tx("20261001", "-10.00", "A", "UM")));
    JsonNode two =
        api.uploadOk(
            actor.session(),
            actor.accountId(),
            ofx(
                new Tx("20261002", "-20.00", "B", "DOIS"),
                new Tx("20261003", "-30.00", "C", "TRES")));
    // As duas foram propostas antes de qualquer confirmação, então as duas pedem importar o B.
    assertThat(line(two, 1).get("proposta").asString()).isEqualTo("importar");
    api.confirm(actor.session(), one.get("id").asString(), null);

    MockHttpServletResponse response = api.confirm(actor.session(), two.get("id").asString(), null);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(body(response).get("duplicadas").asInt()).isEqualTo(1);
    assertThat(body(response).get("criadas").asInt()).isEqualTo(1);
    assertThat(entryCount()).isEqualTo(3);
  }

  @Test
  void reconcilingDoesNotCreateAnEntryAndStampsTheExistingOne() throws Exception {
    String manual =
        api.createEntry(
            actor.session(),
            "despesa",
            3590,
            "2026-10-01",
            actor.accountId(),
            actor.expenseCategoryId());
    JsonNode detail =
        api.uploadOk(
            actor.session(), actor.accountId(), ofx(new Tx("20261003", "-35.90", "F1", "PADARIA")));
    assertThat(line(detail, 1).get("proposta").asString()).isEqualTo("conciliar");

    JsonNode result = body(api.confirm(actor.session(), detail.get("id").asString(), null));

    assertThat(result.get("conciliadas").asInt()).isEqualTo(1);
    assertThat(result.get("criadas").asInt()).isZero();
    assertThat(entryCount()).isEqualTo(1);
    String hash =
        jdbc.sql("SELECT hash_externo FROM lancamento WHERE id = :id")
            .param("id", UUID.fromString(manual))
            .query(String.class)
            .single();
    assertThat(hash).hasSize(64);
    // O lançamento manual não muda de data nem de valor.
    JsonNode kept = entries("2026-10-01", "2026-10-01").get(0);
    assertThat(kept.get("dataCompetencia").asString()).isEqualTo("2026-10-01");

    // Um terceiro extrato que repete a mesma transação ignora a linha.
    JsonNode third =
        api.uploadOk(
            actor.session(),
            actor.accountId(),
            ofx(
                new Tx("20261003", "-35.90", "F1", "PADARIA"),
                new Tx("20261004", "-1.00", "F9", "OUTRA")));
    assertThat(line(third, 1).get("proposta").asString()).isEqualTo("ignorar");
    assertThat(line(third, 1).get("motivo").asString()).isEqualTo("ja_importada");
  }

  @Test
  void honorsTheUsersDecisionsPerLine() throws Exception {
    JsonNode detail =
        api.uploadOk(
            actor.session(),
            actor.accountId(),
            ofx(
                new Tx("20261003", "-35.90", "F1", "PADARIA"),
                new Tx("20261004", "-12.00", "F2", "CAFE"),
                new Tx("20261005", "-8.00", "F3", "BALA")));
    String id = detail.get("id").asString();
    api.decide(actor.session(), id, line(detail, 1).get("id").asString(), decision("ignorar"));
    Map<String, Object> custom = decision("importar");
    custom.put("categoriaId", actor.expenseCategoryId());
    custom.put("titulo", "Café da tarde");
    api.decide(actor.session(), id, line(detail, 2).get("id").asString(), custom);

    JsonNode result = body(api.confirm(actor.session(), id, null));

    assertThat(result.get("ignoradas").asInt()).isEqualTo(1);
    assertThat(result.get("criadas").asInt()).isEqualTo(2);
    JsonNode created = entries("2026-10-01", "2026-10-31");
    assertThat(created).hasSize(2);
    for (JsonNode entry : created) {
      if (entry.get("titulo").asString().equals("Café da tarde")) {
        assertThat(entry.get("categoriaId").asString()).isEqualTo(actor.expenseCategoryId());
      }
    }
  }

  @Test
  void aReconciliationWhoseEntryWasDeletedFallsBackToImporting() throws Exception {
    String manual =
        api.createEntry(
            actor.session(),
            "despesa",
            3590,
            "2026-10-01",
            actor.accountId(),
            actor.expenseCategoryId());
    JsonNode detail =
        api.uploadOk(
            actor.session(), actor.accountId(), ofx(new Tx("20261003", "-35.90", "F1", "PADARIA")));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/lancamentos/" + manual)
                .cookie(actor.session()))
        .andReturn();

    JsonNode result = body(api.confirm(actor.session(), detail.get("id").asString(), null));

    assertThat(result.get("criadas").asInt()).isEqualTo(1);
    assertThat(result.get("conciliadas").asInt()).isZero();
    assertThat(entryCount()).isEqualTo(1);
  }

  @Test
  void aRefusedLineUndoesTheWholeConfirmation() throws Exception {
    JsonNode detail =
        api.uploadOk(
            actor.session(),
            actor.accountId(),
            ofx(new Tx("20261003", "-35.90", "F1", "A"), new Tx("20261004", "-12.00", "F2", "B")));
    String id = detail.get("id").asString();
    api.setAccountArchived(actor.session(), actor.accountId());

    MockHttpServletResponse response = api.confirm(actor.session(), id, null);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(entryCount()).isZero();
    assertThat(body(api.find(actor.session(), id)).get("status").asString()).isEqualTo("pendente");
  }

  @Test
  void ignoredAndInvalidLinesCreateNothing() throws Exception {
    JsonNode detail =
        api.uploadOk(
            actor.session(),
            actor.accountId(),
            ofx(
                new Tx("20261003", "-1.005", "F1", "RUIM"),
                new Tx("20261004", "-12.00", "F2", "B")));
    String id = detail.get("id").asString();
    api.decide(actor.session(), id, line(detail, 2).get("id").asString(), decision("ignorar"));

    JsonNode result = body(api.confirm(actor.session(), id, null));

    assertThat(result.get("criadas").asInt()).isZero();
    assertThat(result.get("ignoradas").asInt()).isEqualTo(2);
    assertThat(entryCount()).isZero();
  }
}
