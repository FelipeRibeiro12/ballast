package com.felipe.ballast.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Hoje é quinta, 29/10/2026, ao meio-dia em São Paulo.
@IntegrationTest
class SavingsYieldSpendableTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

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
    clock.freeze(Instant.parse("2026-10-29T15:00:00Z"));
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    support.clearCdi();
  }

  @Test
  void aYieldAlreadyLaunchedEntersTheBalanceOnceAndTheEstimateStartsToday() throws Exception {
    UUID checking = support.checking(actor, "1000.00");
    UUID pot = support.pot(actor, "100000.00", "100");
    mvc.perform(
            put("/posso-gastar/configuracao")
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"diaLimite\":31}"))
        .andReturn();
    // O menor saldo cai na sexta 30, depois da despesa: a estimativa futura é só a de hoje
    // (quinta).
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,
                                    data_competencia, data_caixa, status)
            SELECT :id, :u, 'despesa', 'teste', c.id, :a, 100.00, DATE '2026-10-30',
                   DATE '2026-10-30', 'previsto'
            FROM categoria c WHERE c.usuario_id = :u AND c.tipo = 'despesa' ORDER BY c.id LIMIT 1
            """)
        .param("id", UUID.randomUUID())
        .param("u", actor.user().id())
        .param("a", checking)
        .update();

    job.run();
    JsonNode body =
        JSON.readTree(
            mvc.perform(get("/posso-gastar").cookie(actor.session()))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

    // Rendimento lançado hoje: 100.000 x 0,05% = 50,00, já no saldo realizado.
    assertThat(support.yields(pot).getFirst().amount()).isEqualByComparingTo("50.00");
    // Estimativa de [29, 30) sobre o saldo realizado de hoje, 100.050,00: 50,025 corta em 50,02.
    var estimate = SavingsSupport.expectedYield("100050.00", "100", "0.05", 1);
    assertThat(estimate).isEqualByComparingTo("50.02");
    assertThat(body.get("menorSaldoEm").asString()).isEqualTo("2026-10-30");
    assertThat(body.get("rendimentoCofrinhoCentavos").asLong()).isEqualTo(50_02);
    // 1.000 + 100.000 + 50,00 (lançado, uma vez) - 100,00 + 50,02 (futuro).
    assertThat(body.get("livreCentavos").asLong()).isEqualTo(101_000_02);
  }
}
