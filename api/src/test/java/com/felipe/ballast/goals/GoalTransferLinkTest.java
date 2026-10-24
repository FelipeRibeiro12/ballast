package com.felipe.ballast.goals;

import static com.felipe.ballast.goals.GoalsApi.error;
import static com.felipe.ballast.goals.GoalsApi.goal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Aporte ligado à transferência que moveu o dinheiro (ADR-057): o vínculo é opcional, o banco
// recusa apagar a transferência vinculada, e cada transferência liga no máximo um aporte.
@IntegrationTest
class GoalTransferLinkTest {

  @Autowired MockMvc mvc;
  private GoalsApi api;
  private Cookie session;
  private String goalId;
  private String transferId;
  private String fromAccountId;

  @BeforeEach
  void setUp() throws Exception {
    api = new GoalsApi(mvc);
    session = api.newSession();
    goalId = api.createOk(session, goal("Viagem", 1_000_000)).get("id").asString();
    fromAccountId = account("Corrente", "corrente");
    String savings = account("Poupança", "poupanca");
    transferId = transfer(fromAccountId, savings);
  }

  private String account(String name, String type) throws Exception {
    MockHttpServletResponse response =
        api.send(session, post("/contas"), Map.of("nome", name, "tipo", type));
    return GoalsApi.body(GoalsApi.expect(response, 201)).get("id").asString();
  }

  private String transfer(String from, String to) throws Exception {
    MockHttpServletResponse response =
        api.send(
            session,
            post("/transferencias"),
            Map.of(
                "contaOrigemId",
                from,
                "contaDestinoId",
                to,
                "valorCentavos",
                50_000,
                "data",
                "2026-01-10"));
    return GoalsApi.body(GoalsApi.expect(response, 201)).get("id").asString();
  }

  private MockHttpServletResponse depositLinked(String linkedTransfer) throws Exception {
    return api.send(
        session,
        post("/metas/" + goalId + "/aportes"),
        Map.of("valorCentavos", 50_000, "data", "2026-01-10", "transferenciaId", linkedTransfer));
  }

  private MockHttpServletResponse deleteTransfer(String id) throws Exception {
    return api.send(session, delete("/transferencias/" + id), null);
  }

  private JsonNode deposits() throws Exception {
    return GoalsApi.body(GoalsApi.expect(api.deposits(session, goalId), 200));
  }

  @Test
  void aDepositKeepsTheLinkAndShowsItInTheList() throws Exception {
    GoalsApi.expect(depositLinked(transferId), 201);
    api.depositOk(session, goalId, 1_000, "2026-01-11");

    JsonNode list = deposits();

    assertThat(list).hasSize(2);
    assertThat(list.get(0).get("transferenciaId").isNull()).isTrue();
    assertThat(list.get(1).get("transferenciaId").asString()).isEqualTo(transferId);
  }

  @Test
  void aTransferLinkedToADepositCannotBeDeleted() throws Exception {
    GoalsApi.expect(depositLinked(transferId), 201);

    MockHttpServletResponse response = deleteTransfer(transferId);

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(error(response)).isEqualTo("transferência vinculada a aporte");
    assertThat(api.send(session, get("/transferencias/" + transferId), null).getStatus())
        .isEqualTo(200);
  }

  @Test
  void deletingTheDepositOrTheGoalFreesTheTransfer() throws Exception {
    GoalsApi.expect(depositLinked(transferId), 201);
    String depositId = deposits().get(0).get("id").asString();

    GoalsApi.expect(api.removeDeposit(session, goalId, depositId), 204);

    assertThat(deleteTransfer(transferId).getStatus()).isEqualTo(204);

    String savings = account("Outra", "poupanca");
    String second = transfer(fromAccountId, savings);
    GoalsApi.expect(depositLinked(second), 201);
    GoalsApi.expect(api.remove(session, goalId), 204);
    assertThat(deleteTransfer(second).getStatus()).isEqualTo(204);
  }

  @Test
  void aTransferLinksAtMostOneDeposit() throws Exception {
    GoalsApi.expect(depositLinked(transferId), 201);

    MockHttpServletResponse second = depositLinked(transferId);

    assertThat(second.getStatus()).isEqualTo(409);
    assertThat(error(second)).isEqualTo("transferência já vinculada a outro aporte");
    assertThat(deposits()).hasSize(1);
  }

  @Test
  void aTransferOfAnotherUserOrUnknownIsRejectedWithTheSameMessage() throws Exception {
    Cookie other = api.newSession();
    String otherCheck =
        GoalsApi.body(
                GoalsApi.expect(
                    api.send(other, post("/contas"), Map.of("nome", "A", "tipo", "corrente")), 201))
            .get("id")
            .asString();
    String otherSavings =
        GoalsApi.body(
                GoalsApi.expect(
                    api.send(other, post("/contas"), Map.of("nome", "B", "tipo", "poupanca")), 201))
            .get("id")
            .asString();
    String foreign =
        GoalsApi.body(
                GoalsApi.expect(
                    api.send(
                        other,
                        post("/transferencias"),
                        Map.of(
                            "contaOrigemId",
                            otherCheck,
                            "contaDestinoId",
                            otherSavings,
                            "valorCentavos",
                            100,
                            "data",
                            "2026-01-10")),
                    201))
            .get("id")
            .asString();

    for (String id : new String[] {foreign, UUID.randomUUID().toString(), "não-é-uuid"}) {
      MockHttpServletResponse response = depositLinked(id);
      assertThat(response.getStatus()).as(id).isEqualTo(400);
      assertThat(error(response)).isEqualTo("transferenciaId não encontrada");
    }
    assertThat(deposits()).isEmpty();
  }

  @Test
  void deletingTheAccountOfALinkedTransferIsRefusedAndChangesNothing() throws Exception {
    GoalsApi.expect(depositLinked(transferId), 201);

    MockHttpServletResponse response = api.send(session, delete("/contas/" + fromAccountId), null);

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(error(response)).isEqualTo("transferência vinculada a aporte");
    assertThat(api.send(session, get("/transferencias/" + transferId), null).getStatus())
        .isEqualTo(200);
  }
}
