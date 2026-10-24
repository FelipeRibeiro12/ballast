package com.felipe.ballast.fixedincome;

import static com.felipe.ballast.fixedincome.FixedIncomeApi.title;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.crypto.CryptoApi;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class FixedIncomeIdempotencyTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;

  @Test
  void aRetriedTitleIsRecordedOnce() throws Exception {
    CryptoApi api = new CryptoApi(mvc);
    CryptoApi.Actor actor = api.newActor();
    String broker = api.createAccountOk(actor.session(), "Corretora", "investimento");
    String key = UUID.randomUUID().toString();
    var body = title(broker, "lci", "cdi", "95", 100_000, "2026-01-10");

    MockHttpServletResponse first =
        api.send(actor.session(), post("/titulos-renda-fixa").header("Idempotency-Key", key), body);
    MockHttpServletResponse again =
        api.send(actor.session(), post("/titulos-renda-fixa").header("Idempotency-Key", key), body);

    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(again.getStatus()).isEqualTo(201);
    assertThat(again.getContentAsString()).isEqualTo(first.getContentAsString());
    assertThat(
            jdbc.sql("SELECT count(*) FROM titulo_renda_fixa WHERE usuario_id = :id")
                .param("id", actor.user().id())
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }
}
