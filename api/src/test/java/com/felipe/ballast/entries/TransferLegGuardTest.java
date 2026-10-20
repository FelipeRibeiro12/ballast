package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.expenseBody;
import static com.felipe.ballast.entries.EntriesApi.with;
import static com.felipe.ballast.entries.TransfersApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// A perna só muda pela rota de transferência, para o par nunca ficar pela metade.
@IntegrationTest
class TransferLegGuardTest {

  private static final String USE_TRANSFER_ROUTE = "{\"erro\":\"use a rota de transferência\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private EntriesApi api;
  private EntriesApi.Actor actor;
  private String outId;
  private String inId;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    TransfersApi transfers = new TransfersApi(mvc);
    actor = api.newActor();
    String other = api.newAccount(actor.session(), "B " + UUID.randomUUID());
    JsonNode created = transfers.createOk(actor.session(), body(actor.accountId(), other, 900));
    outId = created.get("saidaId").asString();
    inId = created.get("entradaId").asString();
  }

  private long legs() {
    return jdbc.sql(
            "SELECT count(*) FROM lancamento WHERE tipo = 'transferencia' AND usuario_id = :id")
        .param("id", actor.user().id())
        .query(Long.class)
        .single();
  }

  @Test
  void putOnALegIs400AndChangesNothing() throws Exception {
    for (String id : new String[] {outId, inId}) {
      MockHttpServletResponse response =
          api.update(actor.session(), id, with(expenseBody(actor), "valorCentavos", 1));

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(EntriesApi.text(response)).isEqualTo(USE_TRANSFER_ROUTE);
      JsonNode leg = EntriesApi.body(api.find(actor.session(), id));
      assertThat(leg.get("tipo").asString()).isEqualTo("transferencia");
      assertThat(leg.get("valorCentavos").asLong()).isEqualTo(900);
    }
  }

  @Test
  void deleteOnALegIs400AndChangesNothing() throws Exception {
    for (String id : new String[] {outId, inId}) {
      MockHttpServletResponse response = api.remove(actor.session(), id);

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(EntriesApi.text(response)).isEqualTo(USE_TRANSFER_ROUTE);
    }
    assertThat(legs()).isEqualTo(2);
  }

  @Test
  void postWithTypeTransferenciaIs400() throws Exception {
    MockHttpServletResponse response =
        api.create(actor.session(), with(expenseBody(actor), "tipo", "transferencia"));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(legs()).isEqualTo(2);
  }

  @Test
  void putWithTypeTransferenciaOnAnOrdinaryEntryIs400() throws Exception {
    String id = api.createOk(actor.session(), expenseBody(actor)).get("id").asString();

    MockHttpServletResponse response =
        api.update(actor.session(), id, with(expenseBody(actor), "tipo", "transferencia"));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.body(api.find(actor.session(), id)).get("tipo").asString())
        .isEqualTo("despesa");
  }
}
