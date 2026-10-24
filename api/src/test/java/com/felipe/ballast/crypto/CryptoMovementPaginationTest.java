package com.felipe.ballast.crypto;

import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class CryptoMovementPaginationTest {

  private static final String NEXT = "X-Next-Cursor";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  private CryptoApi api;
  private CryptoApi.Actor actor;
  private String bitcoin;

  @BeforeEach
  void setUp() throws Exception {
    api = new CryptoApi(mvc);
    actor = api.newActor();
    bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
  }

  private List<String> ids(MockHttpServletResponse response) throws Exception {
    List<String> ids = new ArrayList<>();
    for (JsonNode item : JSON.readTree(response.getContentAsString())) {
      ids.add(item.get("id").asString());
    }
    return ids;
  }

  @Test
  void walkingTheCursorsGivesTheSameListAsOneBigPage() throws Exception {
    for (String date :
        List.of("2026-03-01", "2026-03-05", "2026-03-05", "2026-03-05", "2026-03-09")) {
      api.moveOk(actor.session(), movement(actor.cryptoId(), bitcoin, "compra", "0.1", date, 1000));
    }
    MockHttpServletResponse all = api.listMovements(actor.session(), "");
    assertThat(all.getHeader(NEXT)).isNull();

    List<String> walked = new ArrayList<>();
    String cursor = null;
    do {
      MockHttpServletResponse page =
          api.listMovements(
              actor.session(), "?limite=2" + (cursor == null ? "" : "&cursor=" + cursor));
      assertThat(page.getStatus()).isEqualTo(200);
      walked.addAll(ids(page));
      cursor = page.getHeader(NEXT);
    } while (cursor != null);

    assertThat(walked).isEqualTo(ids(all)).hasSize(5);
  }

  @Test
  void rejectsABadLimitAndABadCursor() throws Exception {
    MockHttpServletResponse limit = api.listMovements(actor.session(), "?limite=0");
    MockHttpServletResponse cursor = api.listMovements(actor.session(), "?cursor=lixo");

    assertThat(limit.getStatus()).isEqualTo(400);
    assertThat(limit.getContentAsString())
        .isEqualTo("{\"erro\":\"limite deve ser um inteiro de 1 a 1000\"}");
    assertThat(cursor.getStatus()).isEqualTo(400);
    assertThat(cursor.getContentAsString()).isEqualTo("{\"erro\":\"cursor inválido\"}");
  }
}
