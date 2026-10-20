package com.felipe.ballast.entries;

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

// Ler, editar o texto e a categoria, e apagar a compra inteira.
@IntegrationTest
class InstallmentManagementTest {

  private static final String NOT_FOUND = "{\"erro\":\"não encontrado\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private InstallmentsApi api;
  private InstallmentsApi.Shopper shopper;
  private JsonNode purchase;
  private String id;

  @BeforeEach
  void setUp() throws Exception {
    api = new InstallmentsApi(mvc);
    shopper = api.newShopper();
    purchase =
        api.createOk(
            shopper.session(), with(InstallmentsApi.purchaseBody(shopper), "descricao", "Loja X"));
    id = purchase.get("id").asString();
  }

  private long countEntries() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :id")
        .param("id", shopper.actor().user().id())
        .query(Long.class)
        .single();
  }

  private String secondCategory() throws Exception {
    // "Mercado" é a de teste; qualquer outra de despesa serve para trocar.
    return api.entries().categoryId(shopper.session(), "Transporte");
  }

  @Test
  void getReturnsThePurchaseWithTheSameShapeAsTheCreation() throws Exception {
    MockHttpServletResponse response = api.find(shopper.session(), id);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(EntriesApi.body(response)).isEqualTo(purchase);
  }

  @Test
  void getOnAnotherInstallmentOrAMalformedOrUnknownIdIs404WithTheSameBody() throws Exception {
    String second = InstallmentsApi.lancamentoId(purchase, 1);
    for (String bad : new String[] {second, EntriesApi.randomId(), "abc", "1-1-1-1-1"}) {
      MockHttpServletResponse response = api.find(shopper.session(), bad);
      assertThat(response.getStatus()).as(bad).isEqualTo(404);
      assertThat(EntriesApi.text(response)).isEqualTo(NOT_FOUND);
    }
  }

  @Test
  void getAnOrdinaryEntryIdIs404() throws Exception {
    JsonNode entry =
        api.entries().createOk(shopper.session(), EntriesApi.expenseBody(shopper.actor()));

    assertThat(api.find(shopper.session(), entry.get("id").asString()).getStatus()).isEqualTo(404);
  }

  @Test
  void patchChangesTitleDescriptionAndCategoryOfEveryInstallment() throws Exception {
    String category = secondCategory();

    MockHttpServletResponse response =
        api.patch(
            shopper.session(),
            id,
            Map.of("titulo", "Notebook novo", "descricao", "Outra loja", "categoriaId", category));

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = EntriesApi.body(response);
    assertThat(body.get("titulo").asString()).isEqualTo("Notebook novo");
    assertThat(body.get("descricao").asString()).isEqualTo("Outra loja");
    assertThat(body.get("categoriaId").asString()).isEqualTo(category);
    assertThat(body.get("valorTotalCentavos").asLong()).isEqualTo(100_000);
    for (int i = 0; i < 3; i++) {
      JsonNode entry =
          EntriesApi.body(
              api.entries().find(shopper.session(), InstallmentsApi.lancamentoId(purchase, i)));
      assertThat(entry.get("titulo").asString()).isEqualTo("Notebook novo");
      assertThat(entry.get("descricao").asString()).isEqualTo("Outra loja");
      assertThat(entry.get("categoriaId").asString()).isEqualTo(category);
      assertThat(entry.get("dataCaixa").asString())
          .isEqualTo(purchase.get("itens").get(i).get("dataCaixa").asString());
    }
  }

  @Test
  void patchWithOnlyTheTitleKeepsTheRest() throws Exception {
    MockHttpServletResponse response =
        api.patch(shopper.session(), id, Map.of("titulo", "Só o título"));

    JsonNode body = EntriesApi.body(response);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(body.get("titulo").asString()).isEqualTo("Só o título");
    assertThat(body.get("descricao").asString()).isEqualTo("Loja X");
    assertThat(body.get("categoriaId").asString()).isEqualTo(shopper.actor().expenseCategoryId());
  }

  @Test
  void aBlankDescriptionClearsIt() throws Exception {
    JsonNode body = EntriesApi.body(api.patch(shopper.session(), id, Map.of("descricao", "  ")));

    assertThat(body.get("descricao").isNull()).isTrue();
  }

  @Test
  void patchWithoutAnyFieldOrWithAnExtraFieldIs400AndChangesNothing() throws Exception {
    assertThat(api.patch(shopper.session(), id, Map.of()).getStatus()).isEqualTo(400);
    assertThat(api.patch(shopper.session(), id, Map.of("titulo", "x", "parcelas", 5)).getStatus())
        .isEqualTo(400);
    assertThat(api.patch(shopper.session(), id, Map.of("valorTotalCentavos", 5)).getStatus())
        .isEqualTo(400);
    assertThat(api.patch(shopper.session(), id, Map.of("data", "2026-03-11")).getStatus())
        .isEqualTo(400);
    assertThat(EntriesApi.body(api.find(shopper.session(), id))).isEqualTo(purchase);
  }

  @Test
  void patchWithInvalidValuesIs400AndChangesNothing() throws Exception {
    assertThat(api.patch(shopper.session(), id, Map.of("titulo", "")).getStatus()).isEqualTo(400);
    assertThat(api.patch(shopper.session(), id, Map.of("titulo", "x".repeat(101))).getStatus())
        .isEqualTo(400);
    assertThat(api.patch(shopper.session(), id, Map.of("descricao", "x".repeat(501))).getStatus())
        .isEqualTo(400);
    MockHttpServletResponse income =
        api.patch(shopper.session(), id, Map.of("categoriaId", shopper.actor().incomeCategoryId()));
    assertThat(income.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(income)).isEqualTo("{\"erro\":\"categoria não encontrada\"}");
    MockHttpServletResponse malformed =
        api.patch(shopper.session(), id, Map.of("categoriaId", "x"));
    assertThat(malformed.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.body(api.find(shopper.session(), id))).isEqualTo(purchase);
  }

  @Test
  void patchOnAnInstallmentThatIsNotTheFirstIs404() throws Exception {
    MockHttpServletResponse response =
        api.patch(
            shopper.session(), InstallmentsApi.lancamentoId(purchase, 1), Map.of("titulo", "x"));

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(EntriesApi.text(response)).isEqualTo(NOT_FOUND);
  }

  @Test
  void patchStillWorksOnAnArchivedCard() throws Exception {
    api.entries().setAccountArchived(shopper.session(), shopper.cardId(), true);

    assertThat(api.patch(shopper.session(), id, Map.of("titulo", "Corrigido")).getStatus())
        .isEqualTo(200);
  }

  @Test
  void deleteRemovesEveryInstallmentAndTheCardBalanceComesBack() throws Exception {
    assertThat(api.entries().accountBalance(shopper.session(), shopper.cardId()))
        .isEqualTo(-100_000);

    MockHttpServletResponse response = api.remove(shopper.session(), id);

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(countEntries()).isZero();
    assertThat(api.entries().accountBalance(shopper.session(), shopper.cardId())).isZero();
    assertThat(api.find(shopper.session(), id).getStatus()).isEqualTo(404);
  }

  @Test
  void deleteOnAnInstallmentThatIsNotTheFirstOrOnUnknownIdsIs404AndKeepsTheRows() throws Exception {
    for (String bad :
        new String[] {InstallmentsApi.lancamentoId(purchase, 2), EntriesApi.randomId(), "abc"}) {
      MockHttpServletResponse response = api.remove(shopper.session(), bad);
      assertThat(response.getStatus()).as(bad).isEqualTo(404);
      assertThat(EntriesApi.text(response)).isEqualTo(NOT_FOUND);
    }
    assertThat(countEntries()).isEqualTo(3);
  }

  @Test
  void deleteAfterOneInstallmentWasRemovedStillClearsTheRest() throws Exception {
    assertThat(
            api.entries()
                .remove(shopper.session(), InstallmentsApi.lancamentoId(purchase, 1))
                .getStatus())
        .isEqualTo(204);

    assertThat(api.remove(shopper.session(), id).getStatus()).isEqualTo(204);
    assertThat(countEntries()).isZero();
  }

  @Test
  void deleteNeverTouchesAnotherPurchaseOrOrdinaryEntry() throws Exception {
    JsonNode other = api.createOk(shopper.session(), InstallmentsApi.purchaseBody(shopper));
    api.entries().createOk(shopper.session(), EntriesApi.expenseBody(shopper.actor()));

    api.remove(shopper.session(), id);

    assertThat(countEntries()).isEqualTo(4);
    assertThat(api.find(shopper.session(), other.get("id").asString()).getStatus()).isEqualTo(200);
  }

  @Test
  void theSameUuidTypedInUpperCaseStillFindsThePurchase() throws Exception {
    assertThat(api.find(shopper.session(), id.toUpperCase()).getStatus()).isEqualTo(200);
    assertThat(UUID.fromString(id)).isNotNull();
  }
}
