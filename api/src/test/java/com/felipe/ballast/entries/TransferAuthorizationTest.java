package com.felipe.ballast.entries;

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

// Usuário A pedindo recurso de B, em toda rota de transferência.
@IntegrationTest
class TransferAuthorizationTest {

  private static final String NOT_FOUND = "{\"erro\":\"não encontrado\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private EntriesApi api;
  private TransfersApi transfers;
  private EntriesApi.Actor a;
  private EntriesApi.Actor b;
  private String secondAccountOfA;
  private String secondAccountOfB;
  private JsonNode transferOfB;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    transfers = new TransfersApi(mvc);
    a = api.newActor();
    b = api.newActor();
    secondAccountOfA = api.newAccount(a.session(), "A2 " + UUID.randomUUID());
    secondAccountOfB = api.newAccount(b.session(), "B2 " + UUID.randomUUID());
    transferOfB = transfers.createOk(b.session(), body(b.accountId(), secondAccountOfB, 1_000));
  }

  private long countAll() {
    return jdbc.sql(
            "SELECT (SELECT count(*) FROM lancamento) + (SELECT count(*) FROM transferencia)")
        .query(Long.class)
        .single();
  }

  @Test
  void creatingWithAnotherUsersOriginOrDestinationIs400AndWritesNothing() throws Exception {
    long before = countAll();

    MockHttpServletResponse origin =
        transfers.create(a.session(), body(b.accountId(), a.accountId(), 100));
    MockHttpServletResponse destination =
        transfers.create(a.session(), body(a.accountId(), b.accountId(), 100));

    for (MockHttpServletResponse response : new MockHttpServletResponse[] {origin, destination}) {
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"conta não encontrada\"}");
    }
    assertThat(countAll()).isEqualTo(before);
  }

  @Test
  void readingAnotherUsersTransferIs404() throws Exception {
    MockHttpServletResponse response =
        transfers.find(a.session(), transferOfB.get("id").asString());

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(EntriesApi.text(response)).isEqualTo(NOT_FOUND);
  }

  @Test
  void deletingAnotherUsersTransferIs404AndChangesNothing() throws Exception {
    long before = countAll();

    MockHttpServletResponse response =
        transfers.remove(a.session(), transferOfB.get("id").asString());

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(EntriesApi.text(response)).isEqualTo(NOT_FOUND);
    assertThat(countAll()).isEqualTo(before);
    assertThat(api.accountBalance(b.session(), secondAccountOfB)).isEqualTo(1_000);
  }

  @Test
  void unknownTransferHasTheSameBodyAsAnotherUsers() throws Exception {
    MockHttpServletResponse unknown = transfers.find(a.session(), EntriesApi.randomId());

    assertThat(unknown.getStatus()).isEqualTo(404);
    assertThat(EntriesApi.text(unknown)).isEqualTo(NOT_FOUND);
  }

  @Test
  void legsOfAnotherUsersTransferDoNotShowInTheList() throws Exception {
    transfers.createOk(a.session(), body(a.accountId(), secondAccountOfA, 300));

    JsonNode list = EntriesApi.body(api.list(a.session(), "?de=2026-01-01&ate=2026-12-31"));

    assertThat(list).hasSize(2);
    for (JsonNode item : list) {
      assertThat(item.get("transferenciaId").asString())
          .isNotEqualTo(transferOfB.get("id").asString());
    }
    assertThat(api.find(a.session(), transferOfB.get("saidaId").asString()).getStatus())
        .isEqualTo(404);
  }
}
