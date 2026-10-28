package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.with;
import static com.felipe.ballast.entries.EntriesApi.without;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class InstallmentCreationTest {

  private static final long CEILING = 9_007_199_254_740_991L;

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private InstallmentsApi api;
  private InstallmentsApi.Shopper shopper;

  @BeforeEach
  void setUp() throws Exception {
    api = new InstallmentsApi(mvc);
    shopper = api.newShopper();
  }

  private Map<String, Object> body() {
    return InstallmentsApi.purchaseBody(shopper);
  }

  private long countEntries() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :id")
        .param("id", shopper.actor().user().id())
        .query(Long.class)
        .single();
  }

  private void assertRefused(MockHttpServletResponse response, int status, String message)
      throws Exception {
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"" + message + "\"}");
    assertThat(countEntries()).isZero();
  }

  @Test
  void threeInstallmentsOfAThousandSplitTheCentOnTheLastAndLandOnConsecutiveDueDates()
      throws Exception {
    MockHttpServletResponse response = api.create(shopper.session(), body());

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode purchase = EntriesApi.body(response);
    assertThat(purchase.get("titulo").asString()).isEqualTo("Notebook");
    assertThat(purchase.get("descricao").isNull()).isTrue();
    assertThat(purchase.get("valorTotalCentavos").asLong()).isEqualTo(100_000);
    assertThat(purchase.get("parcelas").asInt()).isEqualTo(3);
    assertThat(purchase.get("categoriaId").asString())
        .isEqualTo(shopper.actor().expenseCategoryId());
    assertThat(purchase.get("contaId").asString()).isEqualTo(shopper.cardId());
    assertThat(purchase.get("dataCompra").asString()).isEqualTo("2026-03-10");
    assertThat(purchase.get("criadaEm").asString()).isNotBlank();
    JsonNode items = purchase.get("itens");
    assertThat(items).hasSize(3);
    assertThat(items.get(0).get("lancamentoId").asString())
        .isEqualTo(purchase.get("id").asString());
    assertThat(IntStream.range(0, 3).map(i -> items.get(i).get("parcelaNum").asInt()).toArray())
        .containsExactly(1, 2, 3);
    assertThat(items.get(0).get("valorCentavos").asLong()).isEqualTo(33_333);
    assertThat(items.get(1).get("valorCentavos").asLong()).isEqualTo(33_333);
    assertThat(items.get(2).get("valorCentavos").asLong()).isEqualTo(33_334);
    assertThat(items.get(0).get("dataCaixa").asString()).isEqualTo("2026-03-25");
    assertThat(items.get(1).get("dataCaixa").asString()).isEqualTo("2026-04-27");
    assertThat(items.get(2).get("dataCaixa").asString()).isEqualTo("2026-05-25");
  }

  @Test
  void theRowsAreRealizedExpensesWithTheCompetenceOfThePurchaseAndPointToTheFirst()
      throws Exception {
    JsonNode purchase = api.createOk(shopper.session(), with(body(), "descricao", "À vista não"));
    UUID first = UUID.fromString(purchase.get("id").asString());

    List<Map<String, Object>> rows =
        jdbc.sql(
                "SELECT tipo, status, descricao, data_competencia::text AS competencia,"
                    + " parcela_de_id, parcela_num, parcela_total, valor::text AS valor"
                    + " FROM lancamento WHERE usuario_id = :id ORDER BY parcela_num")
            .param("id", shopper.actor().user().id())
            .query()
            .listOfRows();

    assertThat(rows).hasSize(3);
    for (Map<String, Object> row : rows) {
      assertThat(row.get("tipo")).isEqualTo("despesa");
      assertThat(row.get("status")).isEqualTo("realizado");
      assertThat(row.get("descricao")).isEqualTo("À vista não");
      assertThat(row.get("competencia")).isEqualTo("2026-03-10");
      assertThat(row.get("parcela_de_id")).isEqualTo(first);
      assertThat(((Number) row.get("parcela_total")).intValue()).isEqualTo(3);
    }
    assertThat(rows.get(2).get("valor")).isEqualTo("333.34");
  }

  @Test
  void twelveInstallmentsCrossTheYearAndFebruaryAndAddUpToTheTotal() throws Exception {
    JsonNode purchase =
        api.createOk(
            shopper.session(),
            with(
                with(with(body(), "parcelas", 12), "valorTotalCentavos", 100_001),
                "data",
                "2026-12-20"));

    JsonNode items = purchase.get("itens");
    assertThat(items).hasSize(12);
    // Fechamento 15: a compra de 20/12 entra na fatura que fecha em 15/01 e vence em 25/01.
    assertThat(items.get(0).get("dataCaixa").asString()).isEqualTo("2027-01-25");
    assertThat(items.get(1).get("dataCaixa").asString()).isEqualTo("2027-02-25");
    assertThat(items.get(11).get("dataCaixa").asString()).isEqualTo("2027-12-27");
    long sum = 0;
    for (JsonNode item : items) {
      sum += item.get("valorCentavos").asLong();
    }
    assertThat(sum).isEqualTo(100_001);
    assertThat(items.get(0).get("valorCentavos").asLong()).isEqualTo(8_333);
    assertThat(items.get(11).get("valorCentavos").asLong()).isEqualTo(8_338);
  }

  @Test
  void theCardBalanceDropsByTheWholeTotalAtOnce() throws Exception {
    api.createOk(shopper.session(), body());

    assertThat(api.entries().accountBalance(shopper.session(), shopper.cardId()))
        .isEqualTo(-100_000);
  }

  @Test
  void eachOfTheThreeInstallmentsLandsOnItsOwnConsecutiveInvoice() throws Exception {
    api.createOk(shopper.session(), body());

    JsonNode invoices =
        EntriesApi.body(
                mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/contas/"
                                    + shopper.cardId()
                                    + "/faturas?de=2026-03-01&ate=2026-06-30")
                            .cookie(shopper.session()))
                    .andReturn()
                    .getResponse())
            .get("faturas");

    assertThat(invoices).hasSize(4);
    assertThat(invoices.get(0).get("vencimento").asString()).isEqualTo("2026-03-25");
    assertThat(invoices.get(0).get("totalCentavos").asLong()).isEqualTo(33_333);
    assertThat(invoices.get(1).get("totalCentavos").asLong()).isEqualTo(33_333);
    assertThat(invoices.get(2).get("vencimento").asString()).isEqualTo("2026-05-25");
    assertThat(invoices.get(2).get("totalCentavos").asLong()).isEqualTo(33_334);
    assertThat(invoices.get(3).get("totalCentavos").asLong()).isZero();
  }

  @Test
  void aCheckingAccountIsRefused() throws Exception {
    assertRefused(
        api.create(shopper.session(), with(body(), "contaId", shopper.actor().accountId())),
        400,
        "compra parcelada só em cartão de crédito");
  }

  @Test
  void aSavingsAndACashAccountAreRefused() throws Exception {
    for (String type : List.of("poupanca", "dinheiro")) {
      String created =
          EntriesApi.body(
                  mvc.perform(
                          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                  "/contas")
                              .cookie(shopper.session())
                              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                              .content("{\"nome\":\"" + type + "\",\"tipo\":\"" + type + "\"}"))
                      .andReturn()
                      .getResponse())
              .get("id")
              .asString();

      assertRefused(
          api.create(shopper.session(), with(body(), "contaId", created)),
          400,
          "compra parcelada só em cartão de crédito");
    }
  }

  @Test
  void anAnotherUsersCardAndAMissingAccountLookTheSame() throws Exception {
    InstallmentsApi.Shopper other = api.newShopper();

    assertRefused(
        api.create(shopper.session(), with(body(), "contaId", other.cardId())),
        400,
        "conta não encontrada");
    assertRefused(
        api.create(shopper.session(), with(body(), "contaId", EntriesApi.randomId())),
        400,
        "conta não encontrada");
    assertRefused(
        api.create(shopper.session(), with(body(), "contaId", "não é uuid")),
        400,
        "conta não encontrada");
  }

  @Test
  void anArchivedCardIsRefused() throws Exception {
    api.entries().setAccountArchived(shopper.session(), shopper.cardId(), true);

    assertRefused(api.create(shopper.session(), body()), 400, "conta arquivada");
  }

  @Test
  void anIncomeCategoryAndAnotherUsersCategoryAreRefused() throws Exception {
    InstallmentsApi.Shopper other = api.newShopper();

    assertRefused(
        api.create(
            shopper.session(), with(body(), "categoriaId", shopper.actor().incomeCategoryId())),
        400,
        "categoria não encontrada");
    assertRefused(
        api.create(
            shopper.session(), with(body(), "categoriaId", other.actor().expenseCategoryId())),
        400,
        "categoria não encontrada");
    assertRefused(
        api.create(shopper.session(), with(body(), "categoriaId", "x")),
        400,
        "categoria não encontrada");
  }

  static List<Object> invalidInstallmentCounts() {
    return List.of(0, 1, 49, -3);
  }

  @ParameterizedTest
  @MethodSource("invalidInstallmentCounts")
  void installmentsOutsideTwoToFortyEightAreRefused(Object count) throws Exception {
    assertRefused(
        api.create(shopper.session(), with(body(), "parcelas", count)),
        400,
        "parcelas deve ser um inteiro entre 2 e 48");
  }

  @Test
  void theBoundsTwoAndFortyEightAreAccepted() throws Exception {
    assertThat(api.createOk(shopper.session(), with(body(), "parcelas", 2)).get("itens"))
        .hasSize(2);
    assertThat(api.createOk(shopper.session(), with(body(), "parcelas", 48)).get("itens"))
        .hasSize(48);
  }

  @Test
  void aTotalThatLeavesAZeroCentInstallmentIsRefused() throws Exception {
    assertRefused(
        api.create(shopper.session(), with(with(body(), "valorTotalCentavos", 47), "parcelas", 48)),
        400,
        "cada parcela precisa ter ao menos 1 centavo");
  }

  @Test
  void aTotalOfExactlyOneCentPerInstallmentIsAccepted() throws Exception {
    JsonNode purchase =
        api.createOk(
            shopper.session(), with(with(body(), "valorTotalCentavos", 48), "parcelas", 48));

    assertThat(purchase.get("itens").get(0).get("valorCentavos").asLong()).isEqualTo(1);
  }

  @Test
  void aSecondPurchaseThatPassesTheBalanceCeilingIsRefusedAndWritesNothing() throws Exception {
    api.createOk(shopper.session(), with(body(), "valorTotalCentavos", CEILING));
    long before = countEntries();

    MockHttpServletResponse response =
        api.create(shopper.session(), with(body(), "valorTotalCentavos", 1_000));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"saldo da conta fora do limite\"}");
    assertThat(countEntries()).isEqualTo(before);
    assertThat(api.entries().accountBalance(shopper.session(), shopper.cardId()))
        .isEqualTo(-CEILING);
  }

  @Test
  void aTotalAboveTheSafeIntegerIsRefused() throws Exception {
    assertRefused(
        api.create(shopper.session(), with(body(), "valorTotalCentavos", CEILING + 1)),
        400,
        "valorTotalCentavos deve ser um inteiro entre 1 e 9007199254740991");
  }

  @ParameterizedTest
  @MethodSource("invalidFields")
  void invalidFieldsAreRefusedAtTheEdge(String key, Object value, String message) throws Exception {
    assertRefused(api.create(shopper.session(), with(body(), key, value)), 400, message);
  }

  static List<org.junit.jupiter.params.provider.Arguments> invalidFields() {
    return List.of(
        org.junit.jupiter.params.provider.Arguments.of(
            "valorTotalCentavos",
            0,
            "valorTotalCentavos deve ser um inteiro entre 1 e 9007199254740991"),
        org.junit.jupiter.params.provider.Arguments.of(
            "valorTotalCentavos", 12.5, "requisição inválida"),
        org.junit.jupiter.params.provider.Arguments.of("parcelas", 2.5, "requisição inválida"),
        org.junit.jupiter.params.provider.Arguments.of("parcelas", "3", "requisição inválida"),
        org.junit.jupiter.params.provider.Arguments.of("titulo", "", EntryRequest.TITLE_MESSAGE),
        org.junit.jupiter.params.provider.Arguments.of(
            "titulo", "x".repeat(101), EntryRequest.TITLE_MESSAGE),
        org.junit.jupiter.params.provider.Arguments.of(
            "descricao", "x".repeat(501), EntryRequest.DESCRIPTION_MESSAGE),
        org.junit.jupiter.params.provider.Arguments.of(
            "data", "2026-02-30", EntryRequest.DATE_MESSAGE),
        org.junit.jupiter.params.provider.Arguments.of(
            "data", "10/03/2026", EntryRequest.DATE_MESSAGE));
  }

  @Test
  void missingRequiredFieldsAndUnknownFieldsAreRefused() throws Exception {
    assertThat(api.create(shopper.session(), without(body(), "parcelas")).getStatus())
        .isEqualTo(400);
    assertThat(api.create(shopper.session(), without(body(), "valorTotalCentavos")).getStatus())
        .isEqualTo(400);
    assertThat(api.create(shopper.session(), without(body(), "contaId")).getStatus())
        .isEqualTo(400);
    assertThat(api.create(shopper.session(), without(body(), "categoriaId")).getStatus())
        .isEqualTo(400);
    assertThat(api.create(shopper.session(), without(body(), "data")).getStatus()).isEqualTo(400);
    assertThat(api.create(shopper.session(), without(body(), "titulo")).getStatus()).isEqualTo(400);
    assertThat(api.create(shopper.session(), with(body(), "tipo", "despesa")).getStatus())
        .isEqualTo(400);
    assertThat(
            api.create(shopper.session(), with(body(), "usuarioId", EntriesApi.randomId()))
                .getStatus())
        .isEqualTo(400);
    assertThat(countEntries()).isZero();
  }

  @Test
  void withoutASessionTheAnswerIs401() throws Exception {
    assertThat(api.create(null, body()).getStatus()).isEqualTo(401);
  }
}
