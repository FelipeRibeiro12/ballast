package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.expenseBody;
import static com.felipe.ballast.entries.EntriesApi.incomeBody;
import static com.felipe.ballast.entries.EntriesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

// Lançamento em conta de cartão: a data enviada é a da compra e a data_caixa é o vencimento da
// fatura (ADR-031). O cálculo em si está em CardBillingTest; aqui vale a fiação de ponta a ponta.
@IntegrationTest
class CardEntryTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private EntriesApi api;
  private EntriesApi.Actor actor;
  private String cardId;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    actor = api.newActor();
    cardId = api.newCard(actor.session(), "Cartão " + UUID.randomUUID(), actor.accountId(), 15, 25);
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private Map<String, Object> purchase(String cardAccountId, String date) {
    return with(with(expenseBody(actor), "contaId", cardAccountId), "data", date);
  }

  private record Dates(LocalDate competence, LocalDate cash) {}

  private Dates storedDates(String entryId) {
    return jdbc.sql("SELECT data_competencia, data_caixa FROM lancamento WHERE id = :id")
        .param("id", UUID.fromString(entryId))
        .query(
            (rs, row) ->
                new Dates(
                    rs.getObject("data_competencia", LocalDate.class),
                    rs.getObject("data_caixa", LocalDate.class)))
        .single();
  }

  @Test
  void aCardPurchaseKeepsTheDateAsCompetenceAndPutsTheCashOnTheDueDate() throws Exception {
    JsonNode created = api.createOk(actor.session(), purchase(cardId, "2026-03-10"));

    assertThat(created.get("dataCompetencia").asString()).isEqualTo("2026-03-10");
    assertThat(created.get("dataCaixa").asString()).isEqualTo("2026-03-25");
    assertThat(storedDates(created.get("id").asString()))
        .isEqualTo(new Dates(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 25)));
    JsonNode read = EntriesApi.body(api.find(actor.session(), created.get("id").asString()));
    assertThat(read.get("dataCaixa").asString()).isEqualTo("2026-03-25");
  }

  @Test
  void aNonCardPurchaseKeepsBothDatesEqual() throws Exception {
    JsonNode created = api.createOk(actor.session(), expenseBody(actor));

    assertThat(created.get("dataCompetencia").asString()).isEqualTo("2026-03-10");
    assertThat(created.get("dataCaixa").asString()).isEqualTo("2026-03-10");
  }

  // fechamento, vencimento, compra, caixa esperada
  @ParameterizedTest(name = "F{0} V{1} compra {2} caixa {3}")
  @CsvSource({
    // Fechamento 31 em fevereiro, ano comum e bissexto.
    "31, 10, 2026-02-28, 2026-03-10",
    "31, 10, 2026-03-01, 2026-04-10",
    "31, 10, 2024-02-29, 2024-03-10",
    "31, 10, 2024-03-01, 2024-04-10",
    // Vencimento menor que o fechamento.
    "25, 5, 2026-03-10, 2026-04-05",
    "25, 5, 2026-03-26, 2026-05-05",
    // Compra no próprio dia do fechamento e no dia seguinte.
    "15, 25, 2026-03-15, 2026-03-25",
    "15, 25, 2026-03-16, 2026-04-25",
    // Virada de ano.
    "15, 5, 2026-12-20, 2027-02-05"
  })
  void theThreeRequiredCasesWorkEndToEnd(int closing, int due, String date, String cash)
      throws Exception {
    String card =
        api.newCard(actor.session(), "C " + UUID.randomUUID(), actor.accountId(), closing, due);

    JsonNode created = api.createOk(actor.session(), purchase(card, date));

    assertThat(created.get("dataCompetencia").asString()).isEqualTo(date);
    assertThat(created.get("dataCaixa").asString()).isEqualTo(cash);
  }

  @Test
  void aCardRefundFollowsTheSameRule() throws Exception {
    JsonNode refund =
        api.createOk(
            actor.session(),
            with(with(incomeBody(actor), "contaId", cardId), "data", "2026-03-16"));

    assertThat(refund.get("dataCaixa").asString()).isEqualTo("2026-04-25");
  }

  @Test
  void theCardBalanceGoesNegativeWithThePurchase() throws Exception {
    api.createOk(actor.session(), purchase(cardId, "2026-03-10"));

    assertThat(api.accountBalance(actor.session(), cardId)).isEqualTo(-12_345L);
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isZero();
  }

  @Test
  void theListFiltersByCashDateSoThePurchaseShowsUpInTheDueMonth() throws Exception {
    JsonNode created = api.createOk(actor.session(), purchase(cardId, "2026-03-10"));
    String id = created.get("id").asString();

    JsonNode inPurchaseWindow =
        EntriesApi.body(api.list(actor.session(), "?de=2026-03-01&ate=2026-03-24"));
    JsonNode inDueWindow =
        EntriesApi.body(api.list(actor.session(), "?de=2026-03-25&ate=2026-03-31"));

    assertThat(ids(inPurchaseWindow)).doesNotContain(id);
    assertThat(ids(inDueWindow)).contains(id);
  }

  private static java.util.List<String> ids(JsonNode list) {
    java.util.List<String> ids = new java.util.ArrayList<>();
    for (JsonNode item : list) {
      ids.add(item.get("id").asString());
    }
    return ids;
  }

  @Test
  void theProjectionShowsTheOutflowOnTheDueDateAndNotOnThePurchase() throws Exception {
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
    api.createOk(actor.session(), purchase(cardId, "2026-10-10"));

    MockHttpServletResponse response =
        mvc.perform(
                MockMvcRequestBuilders.get("/saldo-projetado?dias=10&contaId=" + cardId)
                    .cookie(actor.session()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode days = EntriesApi.body(response).get("dias");
    // O primeiro dia da série é 2026-10-19 e o vencimento (fechamento 15, vencimento 25) é dia 25.
    assertThat(days.get(0).get("data").asString()).isEqualTo("2026-10-19");
    assertThat(days.get(0).get("saidasCentavos").asLong()).isZero();
    assertThat(days.get(0).get("saldoCentavos").asLong()).isZero();
    assertThat(days.get(5).get("data").asString()).isEqualTo("2026-10-24");
    assertThat(days.get(5).get("saldoCentavos").asLong()).isZero();
    assertThat(days.get(6).get("data").asString()).isEqualTo("2026-10-25");
    assertThat(days.get(6).get("saidasCentavos").asLong()).isEqualTo(12_345L);
    assertThat(days.get(6).get("saldoCentavos").asLong()).isEqualTo(-12_345L);
  }

  @Test
  void movingAnExpenseFromCheckingToCardAndBackRecalculatesTheCash() throws Exception {
    String id = api.createOk(actor.session(), expenseBody(actor)).get("id").asString();
    assertThat(storedDates(id).cash()).isEqualTo(LocalDate.of(2026, 3, 10));

    MockHttpServletResponse toCard =
        api.update(actor.session(), id, with(expenseBody(actor), "contaId", cardId));

    assertThat(toCard.getStatus()).isEqualTo(200);
    assertThat(EntriesApi.body(toCard).get("dataCaixa").asString()).isEqualTo("2026-03-25");
    assertThat(storedDates(id))
        .isEqualTo(new Dates(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 25)));

    MockHttpServletResponse back = api.update(actor.session(), id, expenseBody(actor));

    assertThat(back.getStatus()).isEqualTo(200);
    assertThat(storedDates(id))
        .isEqualTo(new Dates(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 10)));
  }

  @Test
  void changingTheDateOfACardPurchaseRecalculatesTheCash() throws Exception {
    String id = api.createOk(actor.session(), purchase(cardId, "2026-03-10")).get("id").asString();

    MockHttpServletResponse updated =
        api.update(actor.session(), id, purchase(cardId, "2026-03-16"));

    assertThat(updated.getStatus()).isEqualTo(200);
    assertThat(storedDates(id))
        .isEqualTo(new Dates(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 4, 25)));
  }

  private String plannedOnCard(String competenceAndCash) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,
                                    data_competencia, data_caixa, status)
            VALUES (:id, :userId, 'despesa', 'prevista', :categoryId, :accountId, 100.00,
                    CAST(:date AS date), CAST(:date AS date), 'previsto')
            """)
        .param("id", id)
        .param("userId", actor.user().id())
        .param("categoryId", UUID.fromString(actor.expenseCategoryId()))
        .param("accountId", UUID.fromString(cardId))
        .param("date", competenceAndCash)
        .update();
    return id.toString();
  }

  @Test
  void confirmingAPlannedCardEntryWithADateTurnsItIntoCompetenceAndRecalculatesTheCash()
      throws Exception {
    String id = plannedOnCard("2026-03-10");

    MockHttpServletResponse confirmed =
        api.patch(actor.session(), id, Map.of("status", "realizado", "data", "2026-03-16"));

    assertThat(confirmed.getStatus()).isEqualTo(200);
    JsonNode body = EntriesApi.body(confirmed);
    assertThat(body.get("dataCompetencia").asString()).isEqualTo("2026-03-16");
    assertThat(body.get("dataCaixa").asString()).isEqualTo("2026-04-25");
    assertThat(storedDates(id))
        .isEqualTo(new Dates(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 4, 25)));
  }

  @Test
  void confirmingAPlannedCardEntryWithoutDateKeepsBothDates() throws Exception {
    String id = plannedOnCard("2026-03-10");
    jdbc.sql("UPDATE lancamento SET data_caixa = DATE '2026-03-25' WHERE id = :id")
        .param("id", UUID.fromString(id))
        .update();

    MockHttpServletResponse confirmed =
        api.patch(actor.session(), id, Map.of("status", "realizado"));

    assertThat(confirmed.getStatus()).isEqualTo(200);
    assertThat(storedDates(id))
        .isEqualTo(new Dates(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 25)));
  }

  @Test
  void confirmingAPlannedEntryOutsideCardWithADateKeepsBothDatesEqual() throws Exception {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,
                                    data_competencia, data_caixa, status)
            VALUES (:id, :userId, 'despesa', 'prevista', :categoryId, :accountId, 100.00,
                    DATE '2026-03-10', DATE '2026-03-10', 'previsto')
            """)
        .param("id", id)
        .param("userId", actor.user().id())
        .param("categoryId", UUID.fromString(actor.expenseCategoryId()))
        .param("accountId", UUID.fromString(actor.accountId()))
        .update();

    api.patch(actor.session(), id.toString(), Map.of("status", "realizado", "data", "2026-03-16"));

    assertThat(storedDates(id.toString()))
        .isEqualTo(new Dates(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 16)));
  }

  @Test
  void payingTheCardByTransferZeroesItsBalanceWithEqualDates() throws Exception {
    api.createOk(actor.session(), purchase(cardId, "2026-03-10"));
    TransfersApi transfers = new TransfersApi(mvc);

    JsonNode transfer =
        transfers.createOk(actor.session(), TransfersApi.body(actor.accountId(), cardId, 12_345));

    assertThat(api.accountBalance(actor.session(), cardId)).isZero();
    assertThat(api.accountBalance(actor.session(), actor.accountId())).isEqualTo(-12_345L);
    for (String legId : new String[] {"saidaId", "entradaId"}) {
      Dates leg = storedDates(transfer.get(legId).asString());
      assertThat(leg).isEqualTo(new Dates(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 10)));
    }
  }

  @Test
  void aTransferCannotLeaveFromTheCardAndWritesNothing() throws Exception {
    TransfersApi transfers = new TransfersApi(mvc);
    long entriesBefore = count("lancamento");
    long linksBefore = count("transferencia");

    MockHttpServletResponse response =
        transfers.create(actor.session(), TransfersApi.body(cardId, actor.accountId(), 5_000));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.body(response).get("erro").asString())
        .isEqualTo("cartão não pode ser origem de transferência");
    assertThat(count("lancamento")).isEqualTo(entriesBefore);
    assertThat(count("transferencia")).isEqualTo(linksBefore);
    assertThat(api.accountBalance(actor.session(), cardId)).isZero();
  }

  @ParameterizedTest
  @CsvSource({"corrente", "poupanca", "dinheiro"})
  void anyOwnAccountCanPayTheCard(String type) throws Exception {
    TransfersApi transfers = new TransfersApi(mvc);
    String origin = newAccountOfType(type);

    MockHttpServletResponse response =
        transfers.create(actor.session(), TransfersApi.body(origin, cardId, 5_000));

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(api.accountBalance(actor.session(), cardId)).isEqualTo(5_000L);
    assertThat(api.accountBalance(actor.session(), origin)).isEqualTo(-5_000L);
  }

  private String newAccountOfType(String type) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                MockMvcRequestBuilders.post("/contas")
                    .cookie(actor.session())
                    .contentType("application/json")
                    .content(
                        "{\"nome\":\""
                            + type
                            + UUID.randomUUID()
                            + "\",\"tipo\":\""
                            + type
                            + "\"}"))
            .andReturn()
            .getResponse();
    return EntriesApi.body(response).get("id").asString();
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE usuario_id = :id")
        .param("id", actor.user().id())
        .query(Long.class)
        .single();
  }

  @Test
  void userACannotPostOnUserBsCard() throws Exception {
    EntriesApi.Actor b = api.newActor();
    String cardOfB = api.newCard(b.session(), "Do B", b.accountId(), 15, 25);

    MockHttpServletResponse response = api.create(actor.session(), purchase(cardOfB, "2026-03-10"));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.body(response).get("erro").asString()).isEqualTo("conta não encontrada");
    assertThat(api.accountBalance(b.session(), cardOfB)).isZero();
  }

  @Test
  void userACannotMoveAnEntryToUserBsCard() throws Exception {
    EntriesApi.Actor b = api.newActor();
    String cardOfB = api.newCard(b.session(), "Do B", b.accountId(), 15, 25);
    String id = api.createOk(actor.session(), expenseBody(actor)).get("id").asString();

    MockHttpServletResponse response =
        api.update(actor.session(), id, with(expenseBody(actor), "contaId", cardOfB));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(storedDates(id).cash()).isEqualTo(LocalDate.of(2026, 3, 10));
  }
}
