package com.felipe.ballast.imports;

import static com.felipe.ballast.imports.ImportsApi.body;
import static com.felipe.ballast.imports.ImportsApi.line;
import static com.felipe.ballast.imports.ImportsApi.ofx;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.imports.ImportsApi.Tx;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ImportReviewTest {

  @Autowired MockMvc mvc;
  private ImportsApi api;
  private ImportsApi.Actor actor;
  private String importId;
  private String expenseLine;
  private String incomeLine;

  @BeforeEach
  void setUp() throws Exception {
    api = new ImportsApi(mvc);
    actor = api.newActor();
    JsonNode detail =
        api.uploadOk(
            actor.session(),
            actor.accountId(),
            ofx(
                new Tx("20261003", "-35.90", "F1", "PADARIA"),
                new Tx("20261005", "1500.00", "F2", "SALARIO")));
    importId = detail.get("id").asString();
    expenseLine = line(detail, 1).get("id").asString();
    incomeLine = line(detail, 2).get("id").asString();
  }

  private static Map<String, Object> decision(String action) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("acao", action);
    return body;
  }

  private JsonNode reload() throws Exception {
    return body(api.find(actor.session(), importId));
  }

  @Test
  void ignoringALineChangesTheDecisionAndTheTotals() throws Exception {
    MockHttpServletResponse response =
        api.decide(actor.session(), importId, expenseLine, decision("ignorar"));

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode updated = body(response);
    assertThat(updated.get("decisao").asString()).isEqualTo("ignorar");
    assertThat(updated.get("acao").asString()).isEqualTo("ignorar");
    assertThat(updated.get("proposta").asString()).isEqualTo("importar");
    JsonNode detail = reload();
    assertThat(detail.get("totalImportar").asInt()).isEqualTo(1);
    assertThat(detail.get("totalIgnorar").asInt()).isEqualTo(1);
  }

  @Test
  void importingAcceptsCategoryAndTitle() throws Exception {
    Map<String, Object> body = decision("importar");
    body.put("categoriaId", actor.expenseCategoryId());
    body.put("titulo", "Pão e café");

    JsonNode updated = body(api.decide(actor.session(), importId, expenseLine, body));

    assertThat(updated.get("categoriaId").asString()).isEqualTo(actor.expenseCategoryId());
    assertThat(updated.get("titulo").asString()).isEqualTo("Pão e café");
  }

  @Test
  void theDefaultCategoryIsOutrosForExpensesAndOutrasReceitasForIncome() throws Exception {
    JsonNode detail = reload();

    assertThat(line(detail, 1).get("categoriaId").asString())
        .isEqualTo(api.categoryId(actor.session(), "Outros"));
    assertThat(line(detail, 2).get("categoriaId").asString())
        .isEqualTo(api.categoryId(actor.session(), "Outras receitas"));
  }

  @Test
  void reconcilingNeedsAnEntryOfTheSameAccount() throws Exception {
    String entry =
        api.createEntry(
            actor.session(),
            "despesa",
            3590,
            "2026-10-20",
            actor.accountId(),
            actor.expenseCategoryId());
    Map<String, Object> body = decision("conciliar");
    body.put("lancamentoId", entry);

    JsonNode updated = body(api.decide(actor.session(), importId, expenseLine, body));

    assertThat(updated.get("acao").asString()).isEqualTo("conciliar");
    assertThat(updated.get("lancamentoId").asString()).isEqualTo(entry);

    String otherAccount = api.newAccount(actor.session(), "corrente");
    String elsewhere =
        api.createEntry(
            actor.session(),
            "despesa",
            3590,
            "2026-10-03",
            otherAccount,
            actor.expenseCategoryId());
    Map<String, Object> wrong = decision("conciliar");
    wrong.put("lancamentoId", elsewhere);
    assertThat(api.decide(actor.session(), importId, expenseLine, wrong).getStatus())
        .isEqualTo(404);
  }

  @Test
  void rejectsFieldsThatDoNotBelongToTheChosenAction() throws Exception {
    Map<String, Object> reconcileWithoutEntry = decision("conciliar");
    Map<String, Object> ignoreWithCategory = decision("ignorar");
    ignoreWithCategory.put("categoriaId", actor.expenseCategoryId());
    Map<String, Object> importWithEntry = decision("importar");
    importWithEntry.put("lancamentoId", UUID.randomUUID().toString());
    Map<String, Object> unknownAction = decision("apagar");
    Map<String, Object> extraField = decision("ignorar");
    extraField.put("valorCentavos", 1);

    for (Map<String, Object> body :
        java.util.List.of(
            reconcileWithoutEntry,
            ignoreWithCategory,
            importWithEntry,
            unknownAction,
            extraField)) {
      assertThat(api.decide(actor.session(), importId, expenseLine, body).getStatus())
          .isEqualTo(400);
    }
  }

  @Test
  void aCategoryOfTheOtherTypeIsRefused() throws Exception {
    Map<String, Object> body = decision("importar");
    body.put("categoriaId", actor.incomeCategoryId());

    assertThat(api.decide(actor.session(), importId, expenseLine, body).getStatus()).isEqualTo(400);
  }

  @Test
  void anInvalidLineCanOnlyBeIgnored() throws Exception {
    JsonNode detail =
        api.uploadOk(
            actor.session(), actor.accountId(), ofx(new Tx("20261003", "-1.005", "X1", "RUIM")));
    String id = detail.get("id").asString();
    String invalid = line(detail, 1).get("id").asString();

    assertThat(api.decide(actor.session(), id, invalid, decision("importar")).getStatus())
        .isEqualTo(400);
    assertThat(api.decide(actor.session(), id, invalid, decision("ignorar")).getStatus())
        .isEqualTo(200);
  }

  @Test
  void discardingRemovesTheImport() throws Exception {
    assertThat(api.discard(actor.session(), importId).getStatus()).isEqualTo(204);

    assertThat(api.find(actor.session(), importId).getStatus()).isEqualTo(404);
    assertThat(body(api.list(actor.session()))).isEmpty();
  }

  @Test
  void listsTheImportsWithoutTheLines() throws Exception {
    JsonNode list = body(api.list(actor.session()));

    assertThat(list).hasSize(1);
    assertThat(list.get(0).get("id").asString()).isEqualTo(importId);
    assertThat(list.get(0).has("linhas")).isFalse();
    assertThat(list.get(0).get("totalImportar").asInt()).isEqualTo(2);
  }
}
