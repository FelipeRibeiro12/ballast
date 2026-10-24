package com.felipe.ballast.crypto;

import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class CryptoIdempotencyTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;

  @Test
  void aRetriedCryptoMovementIsRecordedOnce() throws Exception {
    CryptoApi api = new CryptoApi(mvc);
    CryptoApi.Actor actor = api.newActor();
    String bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
    String key = UUID.randomUUID().toString();
    var body = movement(actor.cryptoId(), bitcoin, "compra", "0.5", "2026-03-01", 1000);

    MockHttpServletResponse first =
        api.send(actor.session(), post("/movimentos-cripto").header("Idempotency-Key", key), body);
    MockHttpServletResponse again =
        api.send(actor.session(), post("/movimentos-cripto").header("Idempotency-Key", key), body);

    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(again.getStatus()).isEqualTo(201);
    assertThat(again.getContentAsString()).isEqualTo(first.getContentAsString());
    assertThat(
            jdbc.sql("SELECT count(*) FROM movimento_cripto WHERE usuario_id = :id")
                .param("id", actor.user().id())
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }
}
