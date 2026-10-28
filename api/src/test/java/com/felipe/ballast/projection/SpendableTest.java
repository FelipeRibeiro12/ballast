package com.felipe.ballast.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.entries.CardBilling;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Hoje é 15 de março de 2026 em São Paulo.
@IntegrationTest
class SpendableTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private ProjectionFixture fixture;
  private ProjectionFixture.Actor actor;
  private UUID checking;

  @BeforeEach
  void setUp() throws Exception {
    clock.freeze(Instant.parse("2026-03-15T15:00:00Z"));
    fixture = new ProjectionFixture(mvc, jdbc);
    actor = fixture.newActor();
    checking = fixture.account(actor, 1_000_00);
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    // indice é compartilhada entre as classes de teste: deixar o CDI aqui muda os testes de renda
    // fixa.
    jdbc.sql("DELETE FROM indice WHERE serie = 'cdi' AND data = DATE '2026-03-13'").update();
  }

  private MockHttpServletResponse call(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      Object payload)
      throws Exception {
    request.cookie(actor.session());
    if (payload != null) {
      request
          .contentType(MediaType.APPLICATION_JSON)
          .content(JsonMapper.builder().build().writeValueAsString(payload));
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private void chooseDay(Integer day) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("diaLimite", day);
    assertThat(call(put("/posso-gastar/configuracao"), body).getStatus()).isEqualTo(204);
  }

  @Test
  void whatIsLeftIsTheLowestBalanceUntilTheChosenDay() throws Exception {
    chooseDay(4);
    fixture.expense(actor, checking, "previsto", 300_00, LocalDate.of(2026, 3, 20));
    fixture.incomeIn(
        actor, checking, "Outras receitas", "previsto", 100_00, LocalDate.of(2026, 3, 22));
    fixture.expense(actor, checking, "previsto", 200_00, LocalDate.of(2026, 3, 25));
    // Depois do dia escolhido já é a janela seguinte: não conta.
    fixture.expense(actor, checking, "previsto", 4_000_00, LocalDate.of(2026, 4, 10));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("hoje").asString()).isEqualTo("2026-03-15");
    assertThat(body.get("ate").asString()).isEqualTo("2026-04-04");
    assertThat(body.get("modo").asString()).isEqualTo("dia");
    assertThat(body.get("diaLimite").asInt()).isEqualTo(4);
    // O menor saldo é o do dia 25: 1.000 - 300 + 100 - 200.
    assertThat(body.get("livreCentavos").asLong()).isEqualTo(600_00);
    assertThat(body.get("menorSaldoEm").asString()).isEqualTo("2026-03-25");
    assertThat(body.get("dias").asInt()).isEqualTo(21);
    // 600 / 21 = 28,571...: arredonda para baixo, para nunca prometer mais do que tem.
    assertThat(body.get("porDiaCentavos").asLong()).isEqualTo(28_57);
  }

  @Test
  void theChosenDayIncludesTodayWhenItHasNotPassed() throws Exception {
    chooseDay(15);

    assertThat(fixture.spendableOk(actor.session()).get("ate").asString()).isEqualTo("2026-03-15");
  }

  @Test
  void aDayAfterTheMonthEndsFallsOnTheLastDayOfTheMonth() throws Exception {
    chooseDay(31);
    assertThat(fixture.spendableOk(actor.session()).get("ate").asString()).isEqualTo("2026-03-31");

    // Fevereiro tem 28 dias em 2027: dia 31 corta no 28.
    assertThat(SpendableService.nextOccurrenceOfDay(LocalDate.of(2027, 2, 10), 31))
        .isEqualTo(LocalDate.of(2027, 2, 28));
    assertThat(SpendableService.nextOccurrenceOfDay(LocalDate.of(2028, 2, 10), 30))
        .isEqualTo(LocalDate.of(2028, 2, 29));
    assertThat(SpendableService.nextOccurrenceOfDay(LocalDate.of(2026, 12, 20), 5))
        .isEqualTo(LocalDate.of(2027, 1, 5));
  }

  @Test
  void changingTheDayDoesNotTouchAnyEntry() throws Exception {
    fixture.expense(actor, checking, "previsto", 100_00, LocalDate.of(2026, 3, 31));
    fixture.expense(actor, checking, "previsto", 500_00, LocalDate.of(2026, 4, 1));
    long before =
        jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :u")
            .param("u", actor.user().id())
            .query(Long.class)
            .single();

    chooseDay(31);
    long untilMarch = fixture.spendableOk(actor.session()).get("livreCentavos").asLong();
    chooseDay(10);
    long untilApril = fixture.spendableOk(actor.session()).get("livreCentavos").asLong();

    assertThat(untilMarch).isEqualTo(900_00);
    assertThat(untilApril).isEqualTo(400_00);
    assertThat(
            jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :u")
                .param("u", actor.user().id())
                .query(Long.class)
                .single())
        .isEqualTo(before);
  }

  @Test
  void clearingTheDayGoesBackToTheDefaultWindow() throws Exception {
    chooseDay(4);
    chooseDay(null);

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("modo").asString()).isEqualTo("fim-do-mes");
    assertThat(body.get("diaLimite").isNull()).isTrue();
  }

  @Test
  void refusesADayOutOfRange() throws Exception {
    for (int day : new int[] {0, 32, -1}) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("diaLimite", day);
      assertThat(call(put("/posso-gastar/configuracao"), body).getStatus())
          .as("" + day)
          .isEqualTo(400);
    }
  }

  @Test
  void withoutCardsAndPreferenceTheWindowEndsWithTheMonth() throws Exception {
    fixture.expense(actor, checking, "previsto", 100_00, LocalDate.of(2026, 3, 31));
    fixture.expense(actor, checking, "previsto", 500_00, LocalDate.of(2026, 4, 1));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("modo").asString()).isEqualTo("fim-do-mes");
    assertThat(body.get("ate").asString()).isEqualTo("2026-03-31");
    assertThat(body.get("dias").asInt()).isEqualTo(17);
    assertThat(body.get("livreCentavos").asLong()).isEqualTo(900_00);
  }

  @Test
  void theAnchorCardCycleEndsTheDayBeforeTheNextPayment() throws Exception {
    // Vence dia 11, que em abril de 2026 é sábado: paga na segunda, dia 13, e a janela acaba no 12.
    String account = checking.toString();
    Map<String, Object> card = new LinkedHashMap<>();
    card.put("limiteCentavos", 1_000_000);
    card.put("diaFechamento", 1);
    card.put("diaVencimento", 11);
    card.put("contaPagamentoId", account);
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("nome", "Cartão");
    request.put("tipo", "cartao_credito");
    request.put("cartao", card);
    assertThat(call(post("/contas"), request).getStatus()).isEqualTo(201);

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("modo").asString()).isEqualTo("fatura");
    assertThat(body.get("ate").asString()).isEqualTo("2026-04-12");
  }

  @Test
  void theLatestDueCardIsTheAnchor() {
    CardBilling early = new CardBilling(1, 5);
    CardBilling late = new CardBilling(20, 28);

    // Março de 2026: dia 28 cai num sábado e vai para segunda, dia 30.
    assertThat(SpendableService.nextPayDate(late, LocalDate.of(2026, 3, 15)))
        .isEqualTo(LocalDate.of(2026, 3, 30));
    assertThat(SpendableService.nextPayDate(early, LocalDate.of(2026, 3, 15)))
        .isEqualTo(LocalDate.of(2026, 4, 6));
    // No dia do pagamento o ciclo novo começa: o próximo é o do mês seguinte.
    assertThat(SpendableService.nextPayDate(late, LocalDate.of(2026, 3, 30)))
        .isEqualTo(LocalDate.of(2026, 4, 28));
  }

  private void configure(Integer day, boolean benefits, boolean thirdParties) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("diaLimite", day);
    body.put("incluirBeneficios", benefits);
    body.put("incluirTerceiros", thirdParties);
    assertThat(call(put("/posso-gastar/configuracao"), body).getStatus()).isEqualTo(204);
  }

  private void tagAsThirdParty(long cents) {
    UUID friend = UUID.randomUUID();
    jdbc.sql("INSERT INTO terceiro (id, usuario_id, nome) VALUES (:id, :u, 'Maria')")
        .param("id", friend)
        .param("u", actor.user().id())
        .update();
    jdbc.sql(
            """
            INSERT INTO terceiro_lancamento (lancamento_id, usuario_id, terceiro_id)
            SELECT id, usuario_id, :t FROM lancamento
            WHERE usuario_id = :u AND tipo = 'despesa' AND valor = :v
            """)
        .param("t", friend)
        .param("u", actor.user().id())
        .param("v", java.math.BigDecimal.valueOf(cents, 2))
        .update();
  }

  @Test
  void turningThirdPartiesOnCountsTheirPurchasesAsSpending() throws Exception {
    fixture.expense(actor, checking, "previsto", 300_00, LocalDate.of(2026, 3, 20));
    tagAsThirdParty(300_00);

    configure(31, false, false);
    JsonNode off = fixture.spendableOk(actor.session());
    configure(31, false, true);
    JsonNode on = fixture.spendableOk(actor.session());

    assertThat(off.get("livreCentavos").asLong()).isEqualTo(1_000_00);
    assertThat(off.get("incluirTerceiros").asBoolean()).isFalse();
    assertThat(on.get("livreCentavos").asLong()).isEqualTo(700_00);
    assertThat(on.get("incluirTerceiros").asBoolean()).isTrue();
  }

  @Test
  void turningBenefitsOnAddsTheirBalanceAndEmptiesTheApartList() throws Exception {
    UUID meal = fixture.benefitAccount(actor, "VR", 600_00);
    fixture.expense(actor, meal, "previsto", 50_00, LocalDate.of(2026, 3, 18));
    configure(31, false, false);
    JsonNode off = fixture.spendableOk(actor.session());
    configure(31, true, false);
    JsonNode on = fixture.spendableOk(actor.session());

    assertThat(off.get("livreCentavos").asLong()).isEqualTo(1_000_00);
    assertThat(off.get("beneficios")).hasSize(1);
    assertThat(on.get("livreCentavos").asLong()).isEqualTo(1_550_00);
    assertThat(on.get("beneficios")).isEmpty();
    assertThat(on.get("incluirBeneficios").asBoolean()).isTrue();
  }

  @Test
  void theSwitchesWorkWithoutAChosenDay() throws Exception {
    configure(null, true, true);

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("modo").asString()).isEqualTo("fim-do-mes");
    assertThat(body.get("diaLimite").isNull()).isTrue();
    assertThat(body.get("incluirBeneficios").asBoolean()).isTrue();
    assertThat(body.get("incluirTerceiros").asBoolean()).isTrue();
  }

  @Test
  void aThirdPartyPurchaseIsNotMySpending() throws Exception {
    chooseDay(31);
    fixture.expense(actor, checking, "previsto", 300_00, LocalDate.of(2026, 3, 20));
    fixture.expense(actor, checking, "previsto", 200_00, LocalDate.of(2026, 3, 25));
    UUID friend = UUID.randomUUID();
    jdbc.sql("INSERT INTO terceiro (id, usuario_id, nome) VALUES (:id, :u, 'Maria')")
        .param("id", friend)
        .param("u", actor.user().id())
        .update();
    jdbc.sql(
            """
            INSERT INTO terceiro_lancamento (lancamento_id, usuario_id, terceiro_id)
            SELECT id, usuario_id, :t FROM lancamento
            WHERE usuario_id = :u AND tipo = 'despesa' AND valor = 300.00
            """)
        .param("t", friend)
        .param("u", actor.user().id())
        .update();

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("livreCentavos").asLong()).isEqualTo(800_00);
  }

  @Test
  void aSavingsPotEarnsUntilTheDayOfTheLowestBalance() throws Exception {
    chooseDay(31);
    UUID pot = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO conta (id, usuario_id, nome, tipo, saldo_inicial, percentual_cdi)
            VALUES (:id, :u, 'Cofrinho', 'cofrinho', 100000.00, 100)
            """)
        .param("id", pot)
        .param("u", actor.user().id())
        .update();
    jdbc.sql("INSERT INTO indice (serie, data, valor) VALUES ('cdi', :d, 0.05)")
        .param("d", LocalDate.of(2026, 3, 13))
        .update();
    fixture.expense(actor, checking, "previsto", 100_00, LocalDate.of(2026, 3, 25));

    JsonNode body = fixture.spendableOk(actor.session());

    // 1.000 + 100.000 - 100 = 100.900 no dia 25, mais o rendimento de 7 dias úteis do cofrinho.
    long yield = body.get("rendimentoCofrinhoCentavos").asLong();
    assertThat(yield).isGreaterThan(0);
    assertThat(body.get("livreCentavos").asLong()).isEqualTo(100_900_00 + yield);
    // 100.000 x (1,0005^7 - 1) = 350,52, arredondado para baixo.
    assertThat(yield).isEqualTo(350_52);
  }

  @Test
  void aLaterIncomeDoesNotCoverAnEarlierExpense() throws Exception {
    chooseDay(4);
    fixture.expense(actor, checking, "previsto", 900_00, LocalDate.of(2026, 3, 20));
    fixture.incomeIn(
        actor, checking, "Outras receitas", "previsto", 800_00, LocalDate.of(2026, 3, 30));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("livreCentavos").asLong()).isEqualTo(100_00);
    assertThat(body.get("menorSaldoEm").asString()).isEqualTo("2026-03-20");
  }

  @Test
  void spendingMoreThanThereIsLeavesItNegativeAndNothingPerDay() throws Exception {
    chooseDay(4);
    fixture.expense(actor, checking, "previsto", 1_250_00, LocalDate.of(2026, 3, 28));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("livreCentavos").asLong()).isEqualTo(-250_00);
    assertThat(body.get("porDiaCentavos").isNull()).isTrue();
  }

  @Test
  void anOverdueExpectedEntryIsCountedAndReportedApart() throws Exception {
    fixture.expense(actor, checking, "previsto", 150_00, LocalDate.of(2026, 3, 10));

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("livreCentavos").asLong()).isEqualTo(850_00);
    assertThat(body.get("vencidasCentavos").asLong()).isEqualTo(-150_00);
  }

  @Test
  void aBenefitStaysOutOfTheTotalAndIsShownOnItsOwn() throws Exception {
    UUID meal = fixture.benefitAccount(actor, "VR", 600_00);
    fixture.expense(actor, meal, "previsto", 50_00, LocalDate.of(2026, 3, 18));
    fixture.salary(actor, meal, "previsto", 700_00, LocalDate.of(2026, 3, 20));
    chooseDay(4);

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("livreCentavos").asLong()).isEqualTo(1_000_00);
    JsonNode benefits = body.get("beneficios");
    assertThat(benefits).hasSize(1);
    assertThat(benefits.get(0).get("contaId").asString()).isEqualTo(meal.toString());
    assertThat(benefits.get(0).get("nome").asString()).isEqualTo("VR");
    // O menor saldo do VR até o salário: 600 - 50 antes da recarga.
    assertThat(benefits.get(0).get("livreCentavos").asLong()).isEqualTo(550_00);
  }

  @Test
  void aUserDoesNotSeeTheMoneyOfAnother() throws Exception {
    ProjectionFixture.Actor other = fixture.newActor();
    UUID otherChecking = fixture.account(other, 9_999_00);
    fixture.salary(other, otherChecking, "previsto", 1_00, LocalDate.of(2026, 3, 18));
    fixture.benefitAccount(other, "VA", 300_00);

    JsonNode body = fixture.spendableOk(actor.session());

    assertThat(body.get("livreCentavos").asLong()).isEqualTo(1_000_00);
    assertThat(body.get("beneficios")).isEmpty();
  }

  @Test
  void theRouteNeedsASession() throws Exception {
    assertThat(fixture.spendable(null).getStatus()).isEqualTo(401);
  }
}
