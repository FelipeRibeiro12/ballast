package com.felipe.ballast.crypto;

import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.TestcontainersConfiguration;
import com.github.dockerjava.api.DockerClient;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Container próprio e uma única pausa, como em EntriesFrozenDatabaseTest: toda rota de cripto e de
// renda fixa responde 503 em cerca de 2 s com o banco mudo, sem esperar o socketTimeout de 30 s.
// Os corpos são válidos de propósito, para a rota chegar ao banco e não parar na validação.
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1",
      "ballast.indices.job-enabled=false",
      "ballast.cotacoes.job-enabled=false"
    })
@AutoConfigureMockMvc
@Testcontainers
class CryptoFrozenDatabaseTest {

  private static final String MOMENT = "2026-10-21T15:00:00Z";

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    TestcontainersConfiguration.registerProperties(registry, postgres);
  }

  @Autowired MockMvc mvc;

  private void assertUnavailableQuickly(MockHttpServletRequestBuilder request, Cookie session)
      throws Exception {
    long start = System.nanoTime();
    MockHttpServletResponse response =
        mvc.perform(request.cookie(session)).andReturn().getResponse();
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(response.getStatus()).as(request.toString()).isEqualTo(503);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"serviço indisponível\"}");
    assertThat(elapsed).as(request.toString()).isLessThan(Duration.ofMillis(2500));
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, Object body) throws Exception {
    return request
        .contentType(MediaType.APPLICATION_JSON)
        .content(CryptoApi.JSON.writeValueAsString(body));
  }

  @Test
  @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void everyCryptoRouteAnswers503WithinAboutTwoSecondsWhileTheDatabaseIsFrozen() throws Exception {
    CryptoApi api = new CryptoApi(mvc);
    CryptoApi.Actor actor = api.newActor();
    Cookie session = actor.session();
    String bitcoin = api.createAssetOk(session, CryptoApi.bitcoin());
    String movementId =
        api.moveOk(session, movement(actor.cryptoId(), bitcoin, "compra", "1", "2026-10-20", 100));
    api.quoteOk(session, bitcoin, "70000", MOMENT);
    String broker = api.createAccountOk(session, "Corretora", "investimento");
    Map<String, Object> title =
        Map.of(
            "contaId", broker,
            "tipo", "cdb",
            "nome", "CDB",
            "valorAplicadoCentavos", 100_000,
            "dataAplicacao", "2026-10-20",
            "indexador", "prefixado",
            "taxa", "12");
    DockerClient docker = DockerClientFactory.instance().client();
    docker.pauseContainerCmd(postgres.getContainerId()).exec();
    try {
      assertUnavailableQuickly(get("/ativos-cripto"), session);
      assertUnavailableQuickly(
          json(post("/ativos-cripto"), Map.of("rede", "solana", "simbolo", "SOL", "decimais", 9)),
          session);
      assertUnavailableQuickly(
          json(put("/ativos-cripto/" + bitcoin), Map.of("simbolo", "BTC", "decimais", 8)), session);
      assertUnavailableQuickly(delete("/ativos-cripto/" + UUID.randomUUID()), session);
      assertUnavailableQuickly(
          json(post("/ativos-cripto/" + bitcoin + "/cotacoes"), Map.of("precoUsd", "71000")),
          session);
      assertUnavailableQuickly(
          json(
              put("/ativos-cripto/" + bitcoin + "/cotacoes/" + MOMENT),
              Map.of("precoUsd", "72000")),
          session);
      assertUnavailableQuickly(
          delete("/ativos-cripto/" + bitcoin + "/cotacoes/" + MOMENT), session);
      assertUnavailableQuickly(
          json(
              post("/movimentos-cripto"),
              movement(actor.cryptoId(), bitcoin, "compra", "1", "2026-10-21", 100)),
          session);
      assertUnavailableQuickly(get("/movimentos-cripto"), session);
      assertUnavailableQuickly(delete("/movimentos-cripto/" + movementId), session);
      assertUnavailableQuickly(get("/posicoes-cripto?data=2026-10-22"), session);
      assertUnavailableQuickly(get("/patrimonio?data=2026-10-22"), session);
      assertUnavailableQuickly(get("/patrimonio/mensal"), session);
      assertUnavailableQuickly(get("/titulos-renda-fixa"), session);
      assertUnavailableQuickly(json(post("/titulos-renda-fixa"), title), session);
      assertUnavailableQuickly(
          json(
              post("/titulos-renda-fixa/" + UUID.randomUUID() + "/resgate"),
              Map.of("data", "2026-10-21", "valorCentavos", 100_100)),
          session);
      assertUnavailableQuickly(
          delete("/titulos-renda-fixa/" + UUID.randomUUID() + "/resgate"), session);
      assertUnavailableQuickly(get("/indices"), session);
    } finally {
      docker.unpauseContainerCmd(postgres.getContainerId()).exec();
    }
  }
}
