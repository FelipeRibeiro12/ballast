package com.felipe.ballast.recurrences;

import static com.felipe.ballast.recurrences.RecurrencesApi.monthlyBody;
import static com.felipe.ballast.recurrences.RecurrencesApi.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import com.felipe.ballast.accounts.BenefitApi;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// ADR-034 na recorrência: criar com categoria fora da lista é 400, e o job pula o template cuja
// categoria saiu da lista depois, sem derrubar a rodada.
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class BenefitRecurrenceTest {

  private static final String NOT_PERMITTED = "{\"erro\":\"categoria não permitida nesta conta\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired DataSource dataSource;
  @Autowired TestClock clock;
  @Autowired RecurrenceJob job;
  private RecurrencesApi api;
  private BenefitApi benefits;
  private RecurrenceTestSupport db;
  private RecurrencesApi.Actor actor;
  private String food;
  private String market;
  private String benefit;

  @BeforeEach
  void setUp() throws Exception {
    api = new RecurrencesApi(mvc);
    benefits = new BenefitApi(mvc);
    db = new RecurrenceTestSupport(jdbc);
    actor = api.newActor();
    clock.freeze(Instant.parse("2026-10-19T15:00:00Z"));
    food = api.categoryId(actor.session(), "Alimentação");
    market = actor.expenseCategoryId();
    benefit = benefits.createOk(actor.session(), "VR", List.of(food));
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private Map<String, Object> onBenefit(String categoryId) {
    return with(with(monthlyBody(actor), "contaId", benefit), "categoriaId", categoryId);
  }

  private String createId(Map<String, Object> body) throws Exception {
    return api.createOk(actor.session(), body).get("recorrencia").get("id").asString();
  }

  @Test
  void anExpenseTemplateWithACategoryOutsideTheListIs400AndSavesNothing() throws Exception {
    MockHttpServletResponse response = api.create(actor.session(), onBenefit(market));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(RecurrencesApi.text(response)).isEqualTo(NOT_PERMITTED);
    assertThat(db.countTemplates(actor.user().id())).isZero();
    assertThat(db.countRules(actor.user().id())).isZero();
    assertThat(db.countEntries(actor.user().id())).isZero();
  }

  @Test
  void anExpenseTemplateWithACategoryInsideTheListIs201WithItsOccurrences() throws Exception {
    String id = createId(onBenefit(food));

    assertThat(db.countOccurrences(id)).isEqualTo(12);
  }

  @Test
  void anIncomeTemplateOnABenefitAcceptsAnyCategory() throws Exception {
    Map<String, Object> income =
        with(
            with(onBenefit(actor.incomeCategoryId()), "tipo", "receita"),
            "titulo",
            "Recarga do VR");

    assertThat(api.create(actor.session(), income).getStatus()).isEqualTo(201);
  }

  @Test
  void theJobSkipsTheTemplateWhoseCategoryLeftTheListAndMaterializesTheOthers(CapturedOutput output)
      throws Exception {
    String skipped = createId(onBenefit(food));
    String plain = createId(monthlyBody(actor));
    RecurrencesApi.Actor other = api.newActor();
    String ofAnotherUser =
        api.createOk(other.session(), monthlyBody(other)).get("recorrencia").get("id").asString();
    assertThat(benefits.putIds(actor.session(), benefit, List.of(market)).getStatus())
        .isEqualTo(200);
    clock.advance(Duration.ofDays(31));

    job.run();

    assertThat(db.countOccurrences(skipped)).isEqualTo(12);
    assertThat(db.countOccurrences(plain)).isEqualTo(13);
    assertThat(db.countOccurrences(ofAnotherUser)).isEqualTo(13);
    assertThat(output.getOut())
        .contains("categoria fora da lista da conta")
        .contains("usuario_id=" + actor.user().id())
        .contains("template_id=" + skipped);
    assertThat(output.getOut()).doesNotContain("falha ao materializar");
  }

  @Test
  void theSkippedTemplateWorksAgainOnceTheCategoryIsBackInTheList() throws Exception {
    String id = createId(onBenefit(food));
    benefits.putIds(actor.session(), benefit, List.of(market));
    clock.advance(Duration.ofDays(31));
    job.run();
    assertThat(db.countOccurrences(id)).isEqualTo(12);

    // Direto no banco: avançar o relógio mais de 7 dias expirou a sessão de teste.
    jdbc.sql(
            "INSERT INTO conta_categoria_permitida (conta_id, categoria_id, usuario_id)"
                + " VALUES (:account, :category, :userId)")
        .param("account", UUID.fromString(benefit))
        .param("category", UUID.fromString(food))
        .param("userId", actor.user().id())
        .update();
    job.run();

    assertThat(db.countOccurrences(id)).isEqualTo(13);
  }

  @Test
  void theJobSkipsABenefitTemplateWhoseAccountIsBusyWithinTheShortTimeout(CapturedOutput output)
      throws Exception {
    String id = createId(onBenefit(food));
    clock.advance(Duration.ofDays(31));
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement("SELECT id FROM conta WHERE id = ? FOR UPDATE")) {
        statement.setObject(1, UUID.fromString(benefit));
        statement.executeQuery().close();
      }

      long started = System.nanoTime();
      job.run();
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
      holder.rollback();
    }

    assertThat(db.countOccurrences(id)).isEqualTo(12);
    assertThat(output.getOut()).contains("recorrência pulada por trava ocupada").contains(id);
    assertThat(output.getOut()).doesNotContain("falha ao materializar");

    job.run();

    assertThat(db.countOccurrences(id)).isEqualTo(13);
  }
}
