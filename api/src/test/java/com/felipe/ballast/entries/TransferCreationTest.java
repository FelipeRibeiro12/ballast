package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.incomeBody;
import static com.felipe.ballast.entries.EntriesApi.with;
import static com.felipe.ballast.entries.TransfersApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Saldo, par de pernas e recusas de POST /transferencias (ADR-025).
@IntegrationTest
class TransferCreationTest {

  private static final String PERIOD = "?de=2026-01-01&ate=2026-12-31";
  private static final long CEILING = 9_007_199_254_740_991L;

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private EntriesApi api;
  private TransfersApi transfers;
  private EntriesApi.Actor actor;
  private String accountA;
  private String accountB;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    transfers = new TransfersApi(mvc);
    actor = api.newActor();
    accountA = actor.accountId();
    accountB = api.newAccount(actor.session(), "B " + UUID.randomUUID());
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE usuario_id = :id")
        .param("id", actor.user().id())
        .query(Long.class)
        .single();
  }

  private void assertNothingWritten(long entries, long links) {
    assertThat(count("lancamento")).isEqualTo(entries);
    assertThat(count("transferencia")).isEqualTo(links);
  }

  @Test
  void movesMoneyFromOriginToDestinationAndKeepsTheTotal() throws Exception {
    api.createOk(actor.session(), with(incomeBody(actor), "valorCentavos", 50_000));

    MockHttpServletResponse response =
        transfers.create(actor.session(), body(accountA, accountB, 10_000));

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(api.accountBalance(actor.session(), accountA)).isEqualTo(40_000);
    assertThat(api.accountBalance(actor.session(), accountB)).isEqualTo(10_000);
    assertThat(
            api.accountBalance(actor.session(), accountA)
                + api.accountBalance(actor.session(), accountB))
        .isEqualTo(50_000);
  }

  @Test
  void deletingTheTransferGivesTheMoneyBack() throws Exception {
    api.createOk(actor.session(), with(incomeBody(actor), "valorCentavos", 50_000));
    String id =
        transfers.createOk(actor.session(), body(accountA, accountB, 10_000)).get("id").asString();

    MockHttpServletResponse removed = transfers.remove(actor.session(), id);

    assertThat(removed.getStatus()).isEqualTo(204);
    assertThat(api.accountBalance(actor.session(), accountA)).isEqualTo(50_000);
    assertThat(api.accountBalance(actor.session(), accountB)).isZero();
    assertNothingWritten(1, 0);
    assertThat(transfers.find(actor.session(), id).getStatus()).isEqualTo(404);
  }

  @Test
  void accountListsShowTheSameBalanceAsTheDetail() throws Exception {
    transfers.createOk(actor.session(), body(accountA, accountB, 2_500));

    MockHttpServletResponse list =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/contas")
                    .cookie(actor.session()))
            .andReturn()
            .getResponse();

    for (JsonNode account : EntriesApi.body(list)) {
      long expected = account.get("id").asString().equals(accountA) ? -2_500 : 2_500;
      assertThat(account.get("saldoCentavos").asLong()).isEqualTo(expected);
      assertThat(api.accountBalance(actor.session(), account.get("id").asString()))
          .isEqualTo(expected);
    }
  }

  @Test
  void responseDescribesTheTransferAndItsLegs() throws Exception {
    JsonNode created =
        transfers.createOk(
            actor.session(), with(body(accountA, accountB, 12_345), "titulo", "Reserva"));

    assertThat(created.get("contaOrigemId").asString()).isEqualTo(accountA);
    assertThat(created.get("contaDestinoId").asString()).isEqualTo(accountB);
    assertThat(created.get("valorCentavos").asLong()).isEqualTo(12_345);
    assertThat(created.get("data").asString()).isEqualTo("2026-03-10");
    assertThat(created.get("titulo").asString()).isEqualTo("Reserva");
    assertThat(created.get("descricao").isNull()).isTrue();
    assertThat(created.get("saidaId").asString()).isNotEqualTo(created.get("entradaId").asString());
    assertThat(created.get("criadaEm").asString()).isNotBlank();
    JsonNode again = EntriesApi.body(transfers.find(actor.session(), created.get("id").asString()));
    assertThat(again).isEqualTo(created);
  }

  @Test
  void bothLegsShareValueDatesAndTitleWithTheRightAccountsAndDirections() throws Exception {
    JsonNode created =
        transfers.createOk(
            actor.session(),
            with(
                with(body(accountA, accountB, 7_777), "titulo", "Poupança"), "descricao", "mês 3"));

    JsonNode out = EntriesApi.body(api.find(actor.session(), created.get("saidaId").asString()));
    JsonNode in = EntriesApi.body(api.find(actor.session(), created.get("entradaId").asString()));

    for (JsonNode leg : List.of(out, in)) {
      assertThat(leg.get("tipo").asString()).isEqualTo("transferencia");
      assertThat(leg.get("valorCentavos").asLong()).isEqualTo(7_777);
      assertThat(leg.get("dataCompetencia").asString()).isEqualTo("2026-03-10");
      assertThat(leg.get("dataCaixa").asString()).isEqualTo("2026-03-10");
      assertThat(leg.get("titulo").asString()).isEqualTo("Poupança");
      assertThat(leg.get("descricao").asString()).isEqualTo("mês 3");
      assertThat(leg.get("categoriaId").isNull()).isTrue();
      assertThat(leg.get("transferenciaId").asString()).isEqualTo(created.get("id").asString());
    }
    assertThat(out.get("contaId").asString()).isEqualTo(accountA);
    assertThat(out.get("direcao").asString()).isEqualTo("saida");
    assertThat(in.get("contaId").asString()).isEqualTo(accountB);
    assertThat(in.get("direcao").asString()).isEqualTo("entrada");
  }

  @Test
  void listShowsTheLegsAsTransferAndLeavesOtherTypesWithNullFields() throws Exception {
    JsonNode income = api.createOk(actor.session(), incomeBody(actor));
    JsonNode created = transfers.createOk(actor.session(), body(accountA, accountB, 100));

    JsonNode list = EntriesApi.body(api.list(actor.session(), PERIOD));

    assertThat(list).hasSize(3);
    for (JsonNode item : list) {
      String id = item.get("id").asString();
      if (id.equals(income.get("id").asString())) {
        assertThat(item.get("tipo").asString()).isEqualTo("receita");
        assertThat(item.get("transferenciaId").isNull()).isTrue();
        assertThat(item.get("direcao").isNull()).isTrue();
      } else {
        assertThat(item.get("tipo").asString()).isEqualTo("transferencia");
        assertThat(item.get("categoriaId").isNull()).isTrue();
        String expected = id.equals(created.get("saidaId").asString()) ? "saida" : "entrada";
        assertThat(item.get("direcao").asString()).isEqualTo(expected);
      }
    }
  }

  @Test
  void blankOrMissingTitleBecomesTransferencia() throws Exception {
    JsonNode missing = transfers.createOk(actor.session(), body(accountA, accountB, 100));
    JsonNode blank =
        transfers.createOk(actor.session(), with(body(accountA, accountB, 100), "titulo", "   "));

    assertThat(missing.get("titulo").asString()).isEqualTo("Transferência");
    assertThat(blank.get("titulo").asString()).isEqualTo("Transferência");
  }

  static Stream<Object[]> invalidBodies() {
    String a = "A";
    return Stream.of(
        new Object[] {"valor zero", "valorCentavos", 0},
        new Object[] {"valor negativo", "valorCentavos", -1},
        new Object[] {"valor acima do teto", "valorCentavos", CEILING + 1},
        new Object[] {"valor decimal", "valorCentavos", "10.5"},
        new Object[] {"data inválida", "data", "2026-02-30"},
        new Object[] {"título longo", "titulo", "x".repeat(101)},
        new Object[] {"descrição longa", "descricao", "x".repeat(501)},
        new Object[] {"origem ausente", "contaOrigemId", null},
        new Object[] {"destino ausente", "contaDestinoId", null},
        new Object[] {"campo desconhecido", "usuarioId", a});
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidBodies")
  void invalidInputIs400AndWritesNothing(String name, String key, Object value) throws Exception {
    MockHttpServletResponse response =
        transfers.create(actor.session(), with(body(accountA, accountB, 100), key, value));

    assertThat(response.getStatus()).isEqualTo(400);
    assertNothingWritten(0, 0);
  }

  @Test
  void sameAccountIs400() throws Exception {
    MockHttpServletResponse response =
        transfers.create(actor.session(), body(accountA, accountA, 100));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"contas devem ser diferentes\"}");
    assertNothingWritten(0, 0);
  }

  @Test
  void archivedOriginOrDestinationIs400() throws Exception {
    api.setAccountArchived(actor.session(), accountB, true);
    MockHttpServletResponse toArchived =
        transfers.create(actor.session(), body(accountA, accountB, 100));
    MockHttpServletResponse fromArchived =
        transfers.create(actor.session(), body(accountB, accountA, 100));

    for (MockHttpServletResponse response : List.of(toArchived, fromArchived)) {
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"conta arquivada\"}");
    }
    assertNothingWritten(0, 0);
  }

  @Test
  void unknownOrMalformedAccountIdIs400WithTheSameMessage() throws Exception {
    MockHttpServletResponse unknownOrigin =
        transfers.create(actor.session(), body(EntriesApi.randomId(), accountB, 100));
    MockHttpServletResponse unknownDestination =
        transfers.create(actor.session(), body(accountA, EntriesApi.randomId(), 100));
    MockHttpServletResponse malformed =
        transfers.create(actor.session(), body("1-1-1-1-1", accountB, 100));

    for (MockHttpServletResponse response : List.of(unknownOrigin, unknownDestination, malformed)) {
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"conta não encontrada\"}");
    }
    assertNothingWritten(0, 0);
  }

  @Test
  void destinationPassingTheCeilingIs400AndWritesNothing() throws Exception {
    api.createOk(
        actor.session(),
        with(
            with(incomeBody(actor), "contaId", accountB), "valorCentavos", 5_000_000_000_000_000L));

    MockHttpServletResponse response =
        transfers.create(actor.session(), body(accountA, accountB, 5_000_000_000_000_000L));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"saldo da conta fora do limite\"}");
    assertNothingWritten(1, 0);
  }

  @Test
  void originPassingTheNegativeCeilingIs400AndWritesNothing() throws Exception {
    api.createOk(
        actor.session(),
        with(
            with(EntriesApi.expenseBody(actor), "contaId", accountA),
            "valorCentavos",
            5_000_000_000_000_000L));

    MockHttpServletResponse response =
        transfers.create(actor.session(), body(accountA, accountB, 5_000_000_000_000_000L));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"saldo da conta fora do limite\"}");
    assertNothingWritten(1, 0);
  }

  @Test
  void noSessionIs401() throws Exception {
    assertThat(transfers.create(null, body(accountA, accountB, 100)).getStatus()).isEqualTo(401);
    assertThat(transfers.find(null, EntriesApi.randomId()).getStatus()).isEqualTo(401);
    assertThat(transfers.remove(null, EntriesApi.randomId()).getStatus()).isEqualTo(401);
  }

  @Test
  void idOutsideTheCanonicalFormIs404() throws Exception {
    assertThat(transfers.find(actor.session(), "1-1-1-1-1").getStatus()).isEqualTo(404);
    assertThat(transfers.remove(actor.session(), "abc").getStatus()).isEqualTo(404);
  }
}
