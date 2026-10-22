package com.felipe.ballast.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class CryptoQuoteTest {

  private static final String INVALID_PRICE =
      "{\"erro\":\"preco deve ser um decimal maior que zero, em texto, com até 12 dígitos inteiros"
          + " e 8 casas\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private CryptoApi api;
  private CryptoApi.Actor actor;
  private String bitcoin;

  @BeforeEach
  void setUp() throws Exception {
    api = new CryptoApi(mvc);
    actor = api.newActor();
    bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private long quotesOf(String assetId) {
    return jdbc.sql("SELECT count(*) FROM cotacao WHERE ativo_id = :id::uuid")
        .param("id", assetId)
        .query(Long.class)
        .single();
  }

  @Test
  void savesAManualQuoteAndAnswersThePriceAsText() throws Exception {
    MockHttpServletResponse created =
        api.quote(
            actor.session(),
            bitcoin,
            Map.of("preco", "350000.12345678", "momento", "2026-10-21T15:00:00-03:00"));

    assertThat(created.getStatus()).isEqualTo(201);
    JsonNode body = CryptoApi.body(created);
    assertThat(body.get("ativoId").asString()).isEqualTo(bitcoin);
    assertThat(body.get("preco").isString()).isTrue();
    assertThat(body.get("preco").asString()).isEqualTo("350000.12345678");
    assertThat(body.get("fonte").asString()).isEqualTo("manual");
    assertThat(Instant.parse(body.get("momento").asString()))
        .isEqualTo(OffsetDateTime.parse("2026-10-21T15:00:00-03:00").toInstant());
    BigDecimal stored =
        jdbc.sql("SELECT preco FROM cotacao WHERE ativo_id = :id::uuid")
            .param("id", bitcoin)
            .query(BigDecimal.class)
            .single();
    assertThat(stored).isEqualByComparingTo("350000.12345678");
  }

  @Test
  void answersThePriceWithoutTrailingZeros() throws Exception {
    JsonNode body =
        CryptoApi.body(
            api.quote(
                actor.session(),
                bitcoin,
                Map.of("preco", "350000.50", "momento", "2026-10-21T15:00:00Z")));

    assertThat(body.get("preco").asString()).isEqualTo("350000.5");
  }

  @Test
  void withoutAMomentUsesTheTimeOfTheRequest() throws Exception {
    Instant now = Instant.parse("2026-10-21T18:30:00Z");
    clock.freeze(now);

    JsonNode body = CryptoApi.body(api.quote(actor.session(), bitcoin, Map.of("preco", "1")));

    assertThat(Instant.parse(body.get("momento").asString())).isEqualTo(now);
  }

  @Test
  void refusesAPriceThatIsNotAPositiveDecimalInText() throws Exception {
    List<String> bad =
        List.of(
            "0",
            "0.00000000",
            "-1",
            "+1",
            "1e3",
            "1.123456789",
            "1234567890123",
            ".5",
            "5.",
            "1,5",
            " 1",
            "",
            "NaN");
    for (String price : bad) {
      MockHttpServletResponse refused = api.quote(actor.session(), bitcoin, Map.of("preco", price));

      assertThat(refused.getStatus()).as(price).isEqualTo(400);
      assertThat(CryptoApi.text(refused)).as(price).isEqualTo(INVALID_PRICE);
    }
    assertThat(api.quote(actor.session(), bitcoin, "{\"preco\":350000.5}").getStatus())
        .isEqualTo(400);
    assertThat(api.quote(actor.session(), bitcoin, "{}").getStatus()).isEqualTo(400);
    assertThat(quotesOf(bitcoin)).isZero();
  }

  @Test
  void acceptsTheLargestPriceThatFits() throws Exception {
    MockHttpServletResponse created =
        api.quote(actor.session(), bitcoin, Map.of("preco", "999999999999.99999999"));

    assertThat(created.getStatus()).isEqualTo(201);
  }

  @Test
  void refusesAMomentWithoutOffsetOrOutOfRange() throws Exception {
    for (String moment :
        List.of(
            "2026-10-21T15:00:00",
            "2026-10-21",
            "1899-12-31T23:00:00Z",
            "2200-01-01T00:00:00Z",
            "ontem")) {
      MockHttpServletResponse refused =
          api.quote(actor.session(), bitcoin, Map.of("preco", "1", "momento", moment));

      assertThat(refused.getStatus()).as(moment).isEqualTo(400);
      assertThat(CryptoApi.text(refused))
          .as(moment)
          .isEqualTo(
              "{\"erro\":\"momento deve ser data e hora ISO-8601 com fuso, ano entre 1900 e"
                  + " 2199\"}");
    }
  }

  @Test
  void refusesAMomentInTheFuture() throws Exception {
    clock.freeze(Instant.parse("2026-10-21T18:00:00Z"));

    MockHttpServletResponse future =
        api.quote(
            actor.session(), bitcoin, Map.of("preco", "1", "momento", "2026-10-21T18:00:01Z"));
    MockHttpServletResponse now =
        api.quote(
            actor.session(), bitcoin, Map.of("preco", "1", "momento", "2026-10-21T18:00:00Z"));

    assertThat(future.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(future)).isEqualTo("{\"erro\":\"momento não pode ser no futuro\"}");
    assertThat(now.getStatus()).isEqualTo(201);
  }

  @Test
  void theSameMomentTwiceIs409() throws Exception {
    api.quoteOk(actor.session(), bitcoin, "1", "2026-10-21T15:00:00Z");

    MockHttpServletResponse again =
        api.quote(
            actor.session(), bitcoin, Map.of("preco", "2", "momento", "2026-10-21T12:00:00-03:00"));

    assertThat(again.getStatus()).isEqualTo(409);
    assertThat(CryptoApi.text(again)).isEqualTo("{\"erro\":\"já existe cotação nesse momento\"}");
    assertThat(quotesOf(bitcoin)).isEqualTo(1);
  }

  @Test
  void anAssetOfAnotherUserMissingOrMalformedIs404() throws Exception {
    CryptoApi.Actor other = api.newActor();
    for (String id : List.of(bitcoin, CryptoApi.randomId(), "nao-e-uuid", "1-1-1-1-1")) {
      MockHttpServletResponse refused = api.quote(other.session(), id, Map.of("preco", "1"));

      assertThat(refused.getStatus()).as(id).isEqualTo(404);
      assertThat(CryptoApi.text(refused)).isEqualTo("{\"erro\":\"não encontrado\"}");
    }
    assertThat(quotesOf(bitcoin)).isZero();
  }
}
