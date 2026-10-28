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

// Uma parcela vista por /lancamentos: o texto e o valor se editam, a conta e a data não.
@IntegrationTest
class InstallmentEntryRulesTest {

  private static final long CEILING = 9_007_199_254_740_991L;

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private InstallmentsApi api;
  private EntriesApi entries;
  private InstallmentsApi.Shopper shopper;
  private JsonNode purchase;
  private String first;
  private String middle;
  private String last;

  @BeforeEach
  void setUp() throws Exception {
    api = new InstallmentsApi(mvc);
    entries = api.entries();
    shopper = api.newShopper();
    purchase = api.createOk(shopper.session(), InstallmentsApi.purchaseBody(shopper));
    first = InstallmentsApi.lancamentoId(purchase, 0);
    middle = InstallmentsApi.lancamentoId(purchase, 1);
    last = InstallmentsApi.lancamentoId(purchase, 2);
  }

  // O corpo de um PUT de parcela: título e categoria da compra, a data é a da compra e a conta é
  // o cartão (ADR-054: título e categoria só se mudam na compra inteira).
  private Map<String, Object> putBody() {
    return with(
        with(
            with(EntriesApi.expenseBody(shopper.actor()), "contaId", shopper.cardId()),
            "titulo",
            "Notebook"),
        "valorCentavos",
        33_333);
  }

  private long countEntries() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :id")
        .param("id", shopper.actor().user().id())
        .query(Long.class)
        .single();
  }

  @Test
  void listingShowsThePurchaseIdTheNumberAndTheTotalOnlyForInstallments() throws Exception {
    JsonNode ordinary =
        entries.createOk(shopper.session(), EntriesApi.expenseBody(shopper.actor()));

    JsonNode second = EntriesApi.body(entries.find(shopper.session(), middle));
    assertThat(second.get("compraParceladaId").asString()).isEqualTo(purchase.get("id").asString());
    assertThat(second.get("parcelaNum").asInt()).isEqualTo(2);
    assertThat(second.get("parcelaTotal").asInt()).isEqualTo(3);
    assertThat(
            EntriesApi.body(entries.find(shopper.session(), first))
                .get("compraParceladaId")
                .asString())
        .isEqualTo(first);
    assertThat(ordinary.get("compraParceladaId").isNull()).isTrue();
    assertThat(ordinary.get("parcelaNum").isNull()).isTrue();
    assertThat(ordinary.get("parcelaTotal").isNull()).isTrue();

    JsonNode listed =
        EntriesApi.body(entries.list(shopper.session(), "?de=2026-03-01&ate=2026-05-31"));
    assertThat(listed).hasSize(4);
    long installments = 0;
    for (JsonNode item : listed) {
      if (!item.get("compraParceladaId").isNull()) {
        installments++;
      }
    }
    assertThat(installments).isEqualTo(3);
  }

  @Test
  void aPostToLancamentosRefusesTheInstallmentFields() throws Exception {
    for (String key :
        new String[] {"compraParceladaId", "parcelaNum", "parcelaTotal", "parcelas"}) {
      MockHttpServletResponse response = entries.create(shopper.session(), with(putBody(), key, 2));
      assertThat(response.getStatus()).as(key).isEqualTo(400);
    }
    assertThat(countEntries()).isEqualTo(3);
  }

  @Test
  void putChangesDescriptionAndValueOfOneInstallmentAndKeepsTheCashDate() throws Exception {
    MockHttpServletResponse response =
        entries.update(
            shopper.session(),
            middle,
            with(with(putBody(), "descricao", "Ajuste"), "valorCentavos", 40_000));

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = EntriesApi.body(response);
    assertThat(body.get("descricao").asString()).isEqualTo("Ajuste");
    assertThat(body.get("valorCentavos").asLong()).isEqualTo(40_000);
    assertThat(body.get("dataCaixa").asString()).isEqualTo("2026-04-27");
    assertThat(body.get("dataCompetencia").asString()).isEqualTo("2026-03-10");
    assertThat(body.get("compraParceladaId").asString()).isEqualTo(first);
    assertThat(body.get("parcelaNum").asInt()).isEqualTo(2);
  }

  @Test
  void putChangingTheTitleIs400AndChangesNothing() throws Exception {
    JsonNode before = EntriesApi.body(entries.find(shopper.session(), middle));

    MockHttpServletResponse response =
        entries.update(shopper.session(), middle, with(putBody(), "titulo", "Ajuste"));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"edite a compra inteira\"}");
    assertThat(EntriesApi.body(entries.find(shopper.session(), middle))).isEqualTo(before);
  }

  @Test
  void putChangingTheCategoryIs400AndChangesNothing() throws Exception {
    String other = entries.categoryId(shopper.session(), "Transporte");
    JsonNode before = EntriesApi.body(entries.find(shopper.session(), middle));

    MockHttpServletResponse response =
        entries.update(shopper.session(), middle, with(putBody(), "categoriaId", other));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"edite a compra inteira\"}");
    assertThat(EntriesApi.body(entries.find(shopper.session(), middle))).isEqualTo(before);
  }

  @Test
  void aChangedInstallmentMovesTheTotalOfThePurchaseAndTheCardBalance() throws Exception {
    entries.update(shopper.session(), middle, with(putBody(), "valorCentavos", 40_000));

    JsonNode read = EntriesApi.body(api.find(shopper.session(), first));

    assertThat(read.get("valorTotalCentavos").asLong()).isEqualTo(33_333 + 40_000 + 33_334);
    assertThat(read.get("itens").get(1).get("valorCentavos").asLong()).isEqualTo(40_000);
    assertThat(entries.accountBalance(shopper.session(), shopper.cardId()))
        .isEqualTo(-(33_333 + 40_000 + 33_334));
  }

  @Test
  void putWithTheSameAccountAndTheDateOfThePurchaseIsAccepted() throws Exception {
    // A data do corpo é a da compra, não a data_caixa que a parcela guarda.
    assertThat(entries.update(shopper.session(), last, putBody()).getStatus()).isEqualTo(200);
  }

  @Test
  void putChangingTheAccountIs400() throws Exception {
    MockHttpServletResponse response =
        entries.update(
            shopper.session(), middle, with(putBody(), "contaId", shopper.actor().accountId()));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"edite a compra inteira\"}");
    assertThat(EntriesApi.body(entries.find(shopper.session(), middle)).get("contaId").asString())
        .isEqualTo(shopper.cardId());
  }

  @Test
  void putChangingTheDateIs400AndChangesNothing() throws Exception {
    JsonNode before = EntriesApi.body(entries.find(shopper.session(), middle));

    MockHttpServletResponse response =
        entries.update(shopper.session(), middle, with(putBody(), "data", "2026-03-11"));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"edite a compra inteira\"}");
    assertThat(EntriesApi.body(entries.find(shopper.session(), middle))).isEqualTo(before);
  }

  @Test
  void putMakingTheInstallmentAnIncomeIs400() throws Exception {
    MockHttpServletResponse response =
        entries.update(shopper.session(), middle, EntriesApi.incomeBody(shopper.actor()));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.body(entries.find(shopper.session(), middle)).get("tipo").asString())
        .isEqualTo("despesa");
  }

  @Test
  void putThatPassesTheBalanceCeilingIs400AndChangesNothing() throws Exception {
    // Um cartão com a compra no teto: subir uma parcela estoura o saldo.
    InstallmentsApi.Shopper big = api.newShopper();
    JsonNode bigPurchase =
        api.createOk(
            big.session(), with(InstallmentsApi.purchaseBody(big), "valorTotalCentavos", CEILING));
    String installment = InstallmentsApi.lancamentoId(bigPurchase, 0);

    MockHttpServletResponse response =
        entries.update(
            big.session(),
            installment,
            with(
                with(
                    with(EntriesApi.expenseBody(big.actor()), "contaId", big.cardId()),
                    "titulo",
                    "Notebook"),
                "valorCentavos",
                CEILING));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"saldo da conta fora do limite\"}");
    assertThat(EntriesApi.body(api.find(big.session(), bigPurchase.get("id").asString())))
        .isEqualTo(bigPurchase);
  }

  @Test
  void editingAnInstallmentThatPushesThePurchaseTotalOverTheCeilingIs400AndChangesNothing()
      throws Exception {
    // Saldo inicial no teto: o saldo do cartão continua dentro da faixa mesmo com a soma fora.
    InstallmentsApi.Shopper rich = api.newShopper();
    jdbc.sql("UPDATE conta SET saldo_inicial = 90071992547409.91 WHERE id = :id::uuid")
        .param("id", rich.cardId())
        .update();
    JsonNode small =
        api.createOk(
            rich.session(), with(InstallmentsApi.purchaseBody(rich), "valorTotalCentavos", 1000));

    MockHttpServletResponse response =
        entries.update(
            rich.session(),
            InstallmentsApi.lancamentoId(small, 0),
            with(
                with(
                    with(EntriesApi.expenseBody(rich.actor()), "contaId", rich.cardId()),
                    "titulo",
                    "Notebook"),
                "valorCentavos",
                CEILING));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"compra fora do limite\"}");
    MockHttpServletResponse read = api.find(rich.session(), small.get("id").asString());
    assertThat(read.getStatus()).isEqualTo(200);
    assertThat(EntriesApi.body(read)).isEqualTo(small);
  }

  @Test
  void readingAPurchaseWhoseTotalIsOverTheCeilingIs400() throws Exception {
    // Estado que o PUT não grava mais, mas que um dado antigo ou outra via poderia deixar.
    jdbc.sql("UPDATE lancamento SET valor = 90071992547409.91 WHERE id = :id::uuid")
        .param("id", first)
        .update();
    jdbc.sql("UPDATE lancamento SET valor = 90071992547409.91 WHERE id = :id::uuid")
        .param("id", middle)
        .update();

    MockHttpServletResponse response = api.find(shopper.session(), first);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"compra fora do limite\"}");
  }

  @Test
  void deletingAMiddleInstallmentRemovesOnlyThatOneAndTheTotalDrops() throws Exception {
    assertThat(entries.remove(shopper.session(), middle).getStatus()).isEqualTo(204);

    JsonNode read = EntriesApi.body(api.find(shopper.session(), first));
    assertThat(read.get("itens")).hasSize(2);
    assertThat(read.get("valorTotalCentavos").asLong()).isEqualTo(33_333 + 33_334);
    assertThat(read.get("parcelas").asInt()).isEqualTo(3);
    assertThat(countEntries()).isEqualTo(2);
    assertThat(entries.accountBalance(shopper.session(), shopper.cardId()))
        .isEqualTo(-(33_333 + 33_334));
  }

  @Test
  void deletingTheFirstInstallmentIs400AndKeepsEverything() throws Exception {
    MockHttpServletResponse response = entries.remove(shopper.session(), first);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"apague a compra inteira\"}");
    assertThat(countEntries()).isEqualTo(3);
  }

  @Test
  void aStatusChangeOnAnInstallmentIs400BecauseItIsAlreadyRealized() throws Exception {
    MockHttpServletResponse response =
        entries.patch(shopper.session(), middle, Map.of("status", "cancelado"));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response))
        .isEqualTo("{\"erro\":\"só ocorrência prevista pode mudar de status\"}");
  }
}
