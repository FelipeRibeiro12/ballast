package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// A logado como A pede, edita e apaga a compra de B: nada muda e a resposta é a mesma de uma
// compra que não existe.
@IntegrationTest
class InstallmentAuthorizationTest {

  private static final String NOT_FOUND = "{\"erro\":\"não encontrado\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private InstallmentsApi api;
  private InstallmentsApi.Shopper a;
  private InstallmentsApi.Shopper b;
  private JsonNode purchaseOfB;
  private String id;

  @BeforeEach
  void setUp() throws Exception {
    api = new InstallmentsApi(mvc);
    a = api.newShopper();
    b = api.newShopper();
    purchaseOfB = api.createOk(b.session(), InstallmentsApi.purchaseBody(b));
    id = purchaseOfB.get("id").asString();
  }

  private long countEntriesOfB() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :id")
        .param("id", b.actor().user().id())
        .query(Long.class)
        .single();
  }

  @Test
  void aCannotReadEditOrDeleteTheCompraOfB() throws Exception {
    MockHttpServletResponse read = api.find(a.session(), id);
    MockHttpServletResponse edit = api.patch(a.session(), id, Map.of("titulo", "invadido"));
    MockHttpServletResponse remove = api.remove(a.session(), id);

    for (MockHttpServletResponse response : new MockHttpServletResponse[] {read, edit, remove}) {
      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(EntriesApi.text(response)).isEqualTo(NOT_FOUND);
    }
    assertThat(countEntriesOfB()).isEqualTo(3);
    assertThat(EntriesApi.body(api.find(b.session(), id))).isEqualTo(purchaseOfB);
  }

  @Test
  void aCannotBuyOnTheCardOfBNorUseHisCategory() throws Exception {
    MockHttpServletResponse onHisCard =
        api.create(a.session(), with(InstallmentsApi.purchaseBody(a), "contaId", b.cardId()));
    MockHttpServletResponse withHisCategory =
        api.create(
            a.session(),
            with(InstallmentsApi.purchaseBody(a), "categoriaId", b.actor().expenseCategoryId()));

    assertThat(onHisCard.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(onHisCard)).isEqualTo("{\"erro\":\"conta não encontrada\"}");
    assertThat(withHisCategory.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(withHisCategory))
        .isEqualTo("{\"erro\":\"categoria não encontrada\"}");
    assertThat(countEntriesOfB()).isEqualTo(3);
    assertThat(api.entries().accountBalance(b.session(), b.cardId())).isEqualTo(-100_000);
  }

  @Test
  void aCannotChangeOrDeleteAnInstallmentOfBThroughLancamentos() throws Exception {
    String installment = InstallmentsApi.lancamentoId(purchaseOfB, 1);

    MockHttpServletResponse edit =
        api.entries()
            .update(
                a.session(),
                installment,
                with(
                    with(EntriesApi.expenseBody(a.actor()), "contaId", b.cardId()), "titulo", "x"));
    MockHttpServletResponse remove = api.entries().remove(a.session(), installment);
    MockHttpServletResponse read = api.entries().find(a.session(), installment);

    for (MockHttpServletResponse response : new MockHttpServletResponse[] {edit, remove, read}) {
      assertThat(response.getStatus()).isEqualTo(404);
    }
    assertThat(countEntriesOfB()).isEqualTo(3);
  }

  @Test
  void everyRouteNeedsASession() throws Exception {
    assertThat(api.find(null, id).getStatus()).isEqualTo(401);
    assertThat(api.patch(null, id, Map.of("titulo", "x")).getStatus()).isEqualTo(401);
    assertThat(api.remove(null, id).getStatus()).isEqualTo(401);
  }
}
