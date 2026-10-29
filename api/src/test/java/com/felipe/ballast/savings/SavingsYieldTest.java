package com.felipe.ballast.savings;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

// O job só roda quando o teste chama run: o scheduling está desligado no @IntegrationTest. Hoje
// é quinta, 29/10/2026, ao meio-dia em São Paulo; 12/10 e 02/11 são feriado, 24 e 25/10 fim de
// semana.
@IntegrationTest
class SavingsYieldTest {

  private static final String TITLE = "Rendimento do cofrinho (estimado)";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  @Autowired SavingsYieldService service;
  private SavingsYieldJob job;
  private SavingsSupport support;
  private SavingsSupport.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    support = new SavingsSupport(mvc, jdbc);
    // O bean do job só existe com a flag ligada; o teste monta o dele sobre o serviço real.
    job = new SavingsYieldJob(service);
    actor = support.newActor();
    support.clearCdi();
    support.publishCdi(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 10), "0.05");
    today("2026-10-29");
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    support.clearCdi();
  }

  private void today(String iso) {
    clock.freeze(Instant.parse(iso + "T15:00:00Z"));
  }

  private static LocalDate d(String iso) {
    return LocalDate.parse(iso);
  }

  @Test
  void launchesTheEstimatedYieldOnceWithTheRightFields() {
    UUID pot = support.pot(actor, "100000.00", "100");

    job.run();

    // 100.000 x 0,05% x 100% de um dia útil (quarta 28) = 50,00 exatos.
    List<SavingsSupport.Yield> yields = support.yields(pot);
    assertThat(yields).hasSize(1);
    assertThat(yields.getFirst().date()).isEqualTo(d("2026-10-29"));
    assertThat(yields.getFirst().amount()).isEqualByComparingTo("50.00");
    assertThat(yields.getFirst().title()).isEqualTo(TITLE);
    var row =
        jdbc.sql(
                "SELECT tipo, status, data_competencia, data_caixa FROM lancamento"
                    + " WHERE conta_id = :c")
            .param("c", pot)
            .query(
                (rs, i) ->
                    List.of(
                        rs.getString("tipo"),
                        rs.getString("status"),
                        rs.getObject("data_competencia", LocalDate.class),
                        rs.getObject("data_caixa", LocalDate.class)))
            .single();
    assertThat(row).containsExactly("receita", "realizado", d("2026-10-29"), d("2026-10-29"));
    assertThat(support.markers(pot)).isEqualTo(1);
    assertThat(support.markersWithoutEntry(pot)).isZero();
  }

  @Test
  void theAmountMatchesAnIndependentCalculation() {
    UUID pot = support.pot(actor, "2345.67", "110");

    job.run();

    assertThat(support.yields(pot).getFirst().amount())
        .isEqualByComparingTo(SavingsSupport.expectedYield("2345.67", "110", "0.05", 1));
  }

  @Test
  void aSecondRunOnTheSameDayDoesNotDuplicate() {
    UUID pot = support.pot(actor, "100000.00", "100");

    job.run();
    job.run();

    assertThat(support.yields(pot)).hasSize(1);
    assertThat(support.markers(pot)).isEqualTo(1);
  }

  @Test
  void mondayAfterAFridayCreditCoversTheWholeWeekendWithOnlyFridayRate() {
    UUID pot = support.pot(actor, "100000.00", "110");
    // Sexta 23 é o último crédito. O que entrou na própria sexta (data_caixa = L) fica fora da
    // base,
    // e o que saiu na quinta 22 entra.
    support.marker(actor, pot, d("2026-10-23"));
    support.realized(actor, pot, "despesa", "1000.00", d("2026-10-22"));
    support.realized(actor, pot, "receita", "5000.00", d("2026-10-23"));
    today("2026-10-26");

    job.run();

    // Só a sexta tem CDI na janela [sex, seg): sábado e domingo não rendem.
    assertThat(support.yields(pot).stream().filter(y -> y.title().equals(TITLE)))
        .singleElement()
        .satisfies(
            y -> {
              assertThat(y.date()).isEqualTo(d("2026-10-26"));
              assertThat(y.amount())
                  .isEqualByComparingTo(SavingsSupport.expectedYield("99000.00", "110", "0.05", 1));
            });
  }

  @Test
  void saturdaySundayAndHolidaysDoNotLaunch() {
    UUID pot = support.pot(actor, "100000.00", "100");

    for (String day : List.of("2026-10-24", "2026-10-25", "2026-11-02")) {
      today(day);
      job.run();
    }

    assertThat(support.yields(pot)).isEmpty();
    assertThat(support.markers(pot)).isZero();
  }

  @Test
  void withoutCdiNothingIsLaunchedAndNoMarkerIsWritten() {
    support.clearCdi();
    UUID pot = support.pot(actor, "100000.00", "100");

    job.run();

    assertThat(support.yields(pot)).isEmpty();
    assertThat(support.markers(pot)).isZero();
  }

  @Test
  void aPotWithoutPercentIsIgnored() {
    UUID pot = support.pot(actor, "100000.00", null);

    job.run();

    assertThat(support.yields(pot)).isEmpty();
    assertThat(support.markers(pot)).isZero();
  }

  @Test
  void anArchivedPotIsIgnored() {
    UUID pot = support.pot(actor, "100000.00", "100");
    jdbc.sql("UPDATE conta SET arquivada = true WHERE id = :id").param("id", pot).update();

    job.run();

    assertThat(support.markers(pot)).isZero();
  }

  @Test
  void zeroBalanceYieldsNothingButMovesTheWindow() {
    UUID pot = support.pot(actor, "0.00", "100");

    job.run();

    assertThat(support.yields(pot)).isEmpty();
    assertThat(support.markers(pot)).isEqualTo(1);
    assertThat(support.markersWithoutEntry(pot)).isEqualTo(1);
  }

  @Test
  void negativeBalanceYieldsNothingButMovesTheWindow() {
    UUID pot = support.pot(actor, "-250.00", "100");

    job.run();

    assertThat(support.yields(pot)).isEmpty();
    assertThat(support.markersWithoutEntry(pot)).isEqualTo(1);
  }

  @Test
  void anAmountThatRoundsToZeroLeavesNoMarkerSoTheWindowAccumulates() {
    // 0,01 x 0,05% = 0,000005: corta para 0,00.
    UUID pot = support.pot(actor, "0.01", "100");

    job.run();

    assertThat(support.yields(pot)).isEmpty();
    assertThat(support.markers(pot)).isZero();
  }

  @Test
  void roundsDownNeverUp() {
    // 1.000 x 0,0555% = 0,555: o centavo cortado dá 0,55, arredondar para cima daria 0,56.
    support.publishCdi(d("2026-10-28"), "0.0555");
    UUID pot = support.pot(actor, "1000.00", "100");

    job.run();

    assertThat(support.yields(pot).getFirst().amount()).isEqualByComparingTo("0.55");
  }

  @Test
  void catchesUpMissedDaysLimitedToSevenDaysBack() {
    UUID pot = support.pot(actor, "100000.00", "100");
    // A API ficou fora desde segunda 19: terça 20 e quarta 21 estão além dos 7 dias de hoje.
    support.marker(actor, pot, d("2026-10-19"));

    job.run();

    assertThat(support.yields(pot).stream().map(SavingsSupport.Yield::date))
        .containsExactly(
            d("2026-10-22"),
            d("2026-10-23"),
            d("2026-10-26"),
            d("2026-10-27"),
            d("2026-10-28"),
            d("2026-10-29"));
    // Nada se perde: o primeiro dia lançado leva a janela de 19 a 22 (seg, ter, qua).
    assertThat(support.yields(pot).getFirst().amount())
        .isEqualByComparingTo(SavingsSupport.expectedYield("100000.00", "100", "0.05", 3));
  }

  @Test
  void theFirstRunOfAnAccountOnlyDoesToday() {
    UUID pot = support.pot(actor, "100000.00", "100");

    job.run();

    assertThat(support.yields(pot)).hasSize(1);
    assertThat(support.markers(pot)).isEqualTo(1);
  }

  @Test
  void deletingTheEntryKeepsTheMarkerAndTheJobDoesNotRecreateTheDay() {
    UUID pot = support.pot(actor, "100000.00", "100");
    job.run();
    jdbc.sql("DELETE FROM lancamento WHERE conta_id = :c").param("c", pot).update();

    job.run();

    assertThat(support.yields(pot)).isEmpty();
    assertThat(support.markers(pot)).isEqualTo(1);
    assertThat(support.markersWithoutEntry(pot)).isEqualTo(1);
  }

  @Test
  void aMissingIncomeCategoryLaunchesNothingAndKeepsTheWindowOpen() {
    UUID pot = support.pot(actor, "100000.00", "100");
    jdbc.sql("DELETE FROM categoria WHERE usuario_id = :u AND nome = 'Rendimento'")
        .param("u", actor.user().id())
        .update();

    job.run();

    assertThat(support.yields(pot)).isEmpty();
    assertThat(support.markers(pot)).isZero();
  }

  @Test
  void aPotOfOneUserNeverTouchesTheEntriesOfAnother() throws Exception {
    SavingsSupport.Actor other = support.newActor();
    UUID mine = support.pot(actor, "100000.00", "100");
    UUID theirs = support.pot(other, "200000.00", "100");

    job.run();

    assertThat(support.yields(mine).getFirst().amount()).isEqualByComparingTo("50.00");
    assertThat(support.yields(theirs).getFirst().amount()).isEqualByComparingTo("100.00");
    assertThat(
            jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :u AND conta_id = :c")
                .param("u", actor.user().id())
                .param("c", theirs)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void theBalanceBaseIgnoresEntriesFromTheCreditDayOnwards() {
    UUID pot = support.pot(actor, "1000.00", "100");
    support.realized(actor, pot, "receita", "9000.00", d("2026-10-28"));
    support.realized(actor, pot, "receita", "9000.00", d("2026-10-29"));

    job.run();

    // L é quarta 28: a base é 1.000, sem o que entrou em 28 nem em 29.
    BigDecimal expected = SavingsSupport.expectedYield("1000.00", "100", "0.05", 1);
    assertThat(support.yields(pot).getFirst().amount()).isEqualByComparingTo(expected);
  }
}
