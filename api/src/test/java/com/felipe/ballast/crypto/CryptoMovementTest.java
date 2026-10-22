package com.felipe.ballast.crypto;

import static com.felipe.ballast.crypto.CryptoApi.movement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.felipe.ballast.IntegrationTest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class CryptoMovementTest {

  private static final String SALE_TOO_BIG = "{\"erro\":\"venda maior que a posição\"}";
  private static final String NEGATIVE_AFTER_DELETE =
      "{\"erro\":\"apagar deixaria a posição negativa\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private CryptoApi api;
  private CryptoApi.Actor actor;
  private String bitcoin;

  @BeforeEach
  void setUp() throws Exception {
    api = new CryptoApi(mvc);
    actor = api.newActor();
    bitcoin = api.createAssetOk(actor.session(), CryptoApi.bitcoin());
  }

  private Map<String, Object> buy(String quantity, String date, long cents) {
    return movement(actor.cryptoId(), bitcoin, "compra", quantity, date, cents);
  }

  private Map<String, Object> sell(String quantity, String date, long cents) {
    return movement(actor.cryptoId(), bitcoin, "venda", quantity, date, cents);
  }

  private long movementsOf() {
    return jdbc.sql("SELECT count(*) FROM movimento_cripto WHERE usuario_id = :id")
        .param("id", actor.user().id())
        .query(Long.class)
        .single();
  }

  private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
    Map<String, Object> copy = new LinkedHashMap<>(body);
    copy.put(key, value);
    return copy;
  }

  @Test
  void savesABuyWithTheQuantityAsTextAndTheCostInCents() throws Exception {
    MockHttpServletResponse created =
        api.move(actor.session(), buy("0.12345678", "2026-03-10", 4_000_000));

    assertThat(created.getStatus()).isEqualTo(201);
    JsonNode body = CryptoApi.body(created);
    assertThat(body.get("id").asString()).isNotBlank();
    assertThat(body.get("contaId").asString()).isEqualTo(actor.cryptoId());
    assertThat(body.get("ativoId").asString()).isEqualTo(bitcoin);
    assertThat(body.get("tipo").asString()).isEqualTo("compra");
    assertThat(body.get("quantidade").isString()).isTrue();
    assertThat(body.get("quantidade").asString()).isEqualTo("0.12345678");
    assertThat(body.get("data").asString()).isEqualTo("2026-03-10");
    assertThat(body.get("custoCentavos").asLong()).isEqualTo(4_000_000);
    BigDecimal stored =
        jdbc.sql("SELECT quantidade FROM movimento_cripto WHERE id = :id::uuid")
            .param("id", body.get("id").asString())
            .query(BigDecimal.class)
            .single();
    assertThat(stored).isEqualByComparingTo("0.12345678");
  }

  @Test
  void sellsUpToThePositionAndNotOneUnitMore() throws Exception {
    api.moveOk(actor.session(), buy("1", "2026-03-10", 100_000));
    api.moveOk(actor.session(), sell("0.4", "2026-03-11", 50_000));

    MockHttpServletResponse tooBig =
        api.move(actor.session(), sell("0.60000001", "2026-03-12", 1_000));
    MockHttpServletResponse exact = api.move(actor.session(), sell("0.6", "2026-03-12", 1_000));

    assertThat(tooBig.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(tooBig)).isEqualTo(SALE_TOO_BIG);
    assertThat(exact.getStatus()).isEqualTo(201);
    assertThat(movementsOf()).isEqualTo(3);
  }

  @Test
  void aSaleDatedBeforeTheBuyIsRefused() throws Exception {
    api.moveOk(actor.session(), buy("1", "2026-03-10", 100_000));

    MockHttpServletResponse refused = api.move(actor.session(), sell("1", "2026-03-09", 1_000));

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused)).isEqualTo(SALE_TOO_BIG);
  }

  @Test
  void aBuyAndASaleOnTheSameDayCountTogether() throws Exception {
    api.moveOk(actor.session(), buy("1", "2026-03-10", 100_000));

    assertThat(api.move(actor.session(), sell("1", "2026-03-10", 1_000)).getStatus())
        .isEqualTo(201);
  }

  @Test
  void anEarlierSaleCannotEatAQuantityALaterSaleAlreadyUsed() throws Exception {
    api.moveOk(actor.session(), buy("1", "2026-03-01", 100_000));
    api.moveOk(actor.session(), buy("1", "2026-03-20", 100_000));
    api.moveOk(actor.session(), sell("1.5", "2026-03-25", 1_000));

    // No dia 10 a posição é 1, mas a venda do dia 25 já conta com 1,5 dos 2.
    MockHttpServletResponse refused = api.move(actor.session(), sell("1", "2026-03-10", 1_000));

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused)).isEqualTo(SALE_TOO_BIG);
    assertThat(api.move(actor.session(), sell("0.5", "2026-03-10", 1_000)).getStatus())
        .isEqualTo(201);
  }

  @Test
  void positionsAreSeparatedByAccountAndByAsset() throws Exception {
    String other = api.createAccountOk(actor.session(), "Outra exchange", "cripto");
    Map<String, Object> eth = CryptoApi.bitcoin();
    eth.put("rede", "ethereum");
    eth.put("simbolo", "ETH");
    eth.put("decimais", 18);
    String ether = api.createAssetOk(actor.session(), eth);
    api.moveOk(actor.session(), buy("1", "2026-03-10", 100_000));
    api.moveOk(
        actor.session(), movement(actor.cryptoId(), ether, "compra", "5", "2026-03-10", 100_000));

    MockHttpServletResponse otherAccount =
        api.move(actor.session(), movement(other, bitcoin, "venda", "1", "2026-03-11", 1_000));
    MockHttpServletResponse otherAsset =
        api.move(
            actor.session(), movement(actor.cryptoId(), ether, "venda", "6", "2026-03-11", 1_000));

    assertThat(CryptoApi.text(otherAccount)).isEqualTo(SALE_TOO_BIG);
    assertThat(CryptoApi.text(otherAsset)).isEqualTo(SALE_TOO_BIG);
  }

  @Test
  void refusesMoreDecimalPlacesThanTheAssetHas() throws Exception {
    MockHttpServletResponse refused =
        api.move(actor.session(), buy("0.123456789", "2026-03-10", 1_000));

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused))
        .isEqualTo("{\"erro\":\"quantidade tem mais casas que o ativo permite\"}");
    assertThat(api.move(actor.session(), buy("0.123456780", "2026-03-10", 1_000)).getStatus())
        .as("zero à direita não conta como casa")
        .isEqualTo(201);
  }

  @Test
  void anAssetWithZeroDecimalsTakesOnlyWholeUnits() throws Exception {
    Map<String, Object> body = CryptoApi.bitcoin();
    body.put("rede", "solana");
    body.put("contrato", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");
    body.put("simbolo", "NFT");
    body.put("decimais", 0);
    String whole = api.createAssetOk(actor.session(), body);

    MockHttpServletResponse fraction =
        api.move(
            actor.session(),
            movement(actor.cryptoId(), whole, "compra", "1.5", "2026-03-10", 1_000));
    MockHttpServletResponse unit =
        api.move(
            actor.session(), movement(actor.cryptoId(), whole, "compra", "2", "2026-03-10", 1_000));

    assertThat(fraction.getStatus()).isEqualTo(400);
    assertThat(unit.getStatus()).isEqualTo(201);
  }

  @Test
  void refusesAQuantityThatIsNotAPositiveDecimalInText() throws Exception {
    List<String> bad =
        List.of(
            "0", "0.0", "-1", "+1", "1e3", ".5", "5.", "1,5", "", "123456789012345678901", "abc");
    for (String quantity : bad) {
      MockHttpServletResponse refused =
          api.move(actor.session(), buy(quantity, "2026-03-10", 1_000));

      assertThat(refused.getStatus()).as(quantity).isEqualTo(400);
      assertThat(CryptoApi.text(refused))
          .as(quantity)
          .isEqualTo(
              "{\"erro\":\"quantidade deve ser um decimal maior que zero, em texto, com até 20"
                  + " dígitos inteiros e 18 casas\"}");
    }
    assertThat(
            api.move(actor.session(), with(buy("1", "2026-03-10", 1_000), "quantidade", 1))
                .getStatus())
        .isEqualTo(400);
    assertThat(movementsOf()).isZero();
  }

  @Test
  void refusesBadTypeDateCostAndUndeclaredFields() throws Exception {
    Map<String, Object> ok = buy("1", "2026-03-10", 1_000);
    List<Map<String, Object>> bad =
        List.of(
            with(ok, "tipo", "transferencia"),
            with(ok, "tipo", "Compra"),
            with(ok, "data", "2026-02-30"),
            with(ok, "data", "1899-12-31"),
            with(ok, "data", "10/03/2026"),
            with(ok, "custoCentavos", 0),
            with(ok, "custoCentavos", -1),
            with(ok, "custoCentavos", 9_007_199_254_740_992L),
            with(ok, "custoCentavos", 10.5),
            with(ok, "usuarioId", actor.user().id().toString()));
    for (Map<String, Object> body : bad) {
      assertThat(api.move(actor.session(), body).getStatus()).as(body.toString()).isEqualTo(400);
    }
    for (String field : List.of("contaId", "ativoId", "tipo", "quantidade", "data")) {
      Map<String, Object> missing = new LinkedHashMap<>(ok);
      missing.remove(field);
      assertThat(api.move(actor.session(), missing).getStatus()).as(field).isEqualTo(400);
    }
    assertThat(movementsOf()).isZero();
  }

  @Test
  void acceptsTheLargestCost() throws Exception {
    assertThat(
            api.move(actor.session(), buy("1", "2026-03-10", 9_007_199_254_740_991L)).getStatus())
        .isEqualTo(201);
  }

  @Test
  void anAccountThatIsNotCryptoArchivedOrNotFoundIsRefused() throws Exception {
    CryptoApi.Actor other = api.newActor();
    for (String account :
        List.of(other.cryptoId(), CryptoApi.randomId(), "nao-e-uuid", "1-1-1-1-1")) {
      MockHttpServletResponse refused =
          api.move(actor.session(), with(buy("1", "2026-03-10", 1_000), "contaId", account));

      assertThat(refused.getStatus()).as(account).isEqualTo(400);
      assertThat(CryptoApi.text(refused)).isEqualTo("{\"erro\":\"conta não encontrada\"}");
    }

    MockHttpServletResponse checking =
        api.move(
            actor.session(), with(buy("1", "2026-03-10", 1_000), "contaId", actor.checkingId()));
    assertThat(CryptoApi.text(checking)).isEqualTo("{\"erro\":\"conta deve ser cripto\"}");

    api.send(actor.session(), patch("/contas/" + actor.cryptoId()), Map.of("arquivada", true));
    MockHttpServletResponse archived = api.move(actor.session(), buy("1", "2026-03-10", 1_000));
    assertThat(CryptoApi.text(archived)).isEqualTo("{\"erro\":\"conta arquivada\"}");
    assertThat(movementsOf()).isZero();
  }

  @Test
  void anAssetOfAnotherUserOrNotFoundIsRefused() throws Exception {
    CryptoApi.Actor other = api.newActor();
    String theirs = api.createAssetOk(other.session(), CryptoApi.bitcoin());
    for (String asset : List.of(theirs, CryptoApi.randomId(), "nao-e-uuid")) {
      MockHttpServletResponse refused =
          api.move(actor.session(), with(buy("1", "2026-03-10", 1_000), "ativoId", asset));

      assertThat(refused.getStatus()).as(asset).isEqualTo(400);
      assertThat(CryptoApi.text(refused)).isEqualTo("{\"erro\":\"ativo não encontrado\"}");
    }
  }

  @Test
  void deletesAMovementAndRefusesTheDeleteThatWouldLeaveTheSaleUncovered() throws Exception {
    String firstBuy = api.moveOk(actor.session(), buy("1", "2026-03-01", 100_000));
    String secondBuy = api.moveOk(actor.session(), buy("1", "2026-03-20", 100_000));
    String sale = api.moveOk(actor.session(), sell("1.5", "2026-03-25", 1_000));

    MockHttpServletResponse refused = api.removeMovement(actor.session(), firstBuy);
    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(CryptoApi.text(refused)).isEqualTo(NEGATIVE_AFTER_DELETE);
    assertThat(movementsOf()).isEqualTo(3);

    assertThat(api.removeMovement(actor.session(), sale).getStatus()).isEqualTo(204);
    assertThat(api.removeMovement(actor.session(), firstBuy).getStatus()).isEqualTo(204);
    assertThat(api.removeMovement(actor.session(), secondBuy).getStatus()).isEqualTo(204);
    assertThat(movementsOf()).isZero();
  }

  @Test
  void deletingInAnArchivedAccountIsAllowed() throws Exception {
    String id = api.moveOk(actor.session(), buy("1", "2026-03-01", 100_000));
    api.send(actor.session(), patch("/contas/" + actor.cryptoId()), Map.of("arquivada", true));

    assertThat(api.removeMovement(actor.session(), id).getStatus()).isEqualTo(204);
  }

  @Test
  void deletingAMovementOfAnotherUserMissingOrMalformedIs404() throws Exception {
    String id = api.moveOk(actor.session(), buy("1", "2026-03-01", 100_000));
    CryptoApi.Actor other = api.newActor();
    for (String target : List.of(id, CryptoApi.randomId(), "nao-e-uuid")) {
      MockHttpServletResponse refused = api.removeMovement(other.session(), target);

      assertThat(refused.getStatus()).as(target).isEqualTo(404);
      assertThat(CryptoApi.text(refused)).isEqualTo("{\"erro\":\"não encontrado\"}");
    }
    assertThat(movementsOf()).isEqualTo(1);
  }

  @Test
  void listsByDateNewestFirstWithOptionalFilters() throws Exception {
    String other = api.createAccountOk(actor.session(), "Outra exchange", "cripto");
    String older = api.moveOk(actor.session(), buy("1", "2026-03-01", 100_000));
    String newer = api.moveOk(actor.session(), sell("0.5", "2026-03-05", 60_000));
    String elsewhere =
        api.moveOk(actor.session(), movement(other, bitcoin, "compra", "2", "2026-03-03", 200_000));

    JsonNode all = CryptoApi.body(api.listMovements(actor.session(), ""));
    JsonNode byAccount =
        CryptoApi.body(api.listMovements(actor.session(), "?contaId=" + actor.cryptoId()));
    JsonNode byAsset = CryptoApi.body(api.listMovements(actor.session(), "?ativoId=" + bitcoin));

    assertThat(all)
        .extracting(item -> item.get("id").asString())
        .containsExactly(newer, elsewhere, older);
    assertThat(byAccount)
        .extracting(item -> item.get("id").asString())
        .containsExactly(newer, older);
    assertThat(byAsset).hasSize(3);
  }

  @Test
  void aFilterOfAnotherUserGivesAnEmptyListAndAMalformedOneIs400() throws Exception {
    api.moveOk(actor.session(), buy("1", "2026-03-01", 100_000));
    CryptoApi.Actor other = api.newActor();

    assertThat(CryptoApi.body(api.listMovements(other.session(), ""))).isEmpty();
    assertThat(CryptoApi.body(api.listMovements(actor.session(), "?contaId=" + other.cryptoId())))
        .isEmpty();
    MockHttpServletResponse badAccount = api.listMovements(actor.session(), "?contaId=x");
    MockHttpServletResponse badAsset = api.listMovements(actor.session(), "?ativoId=x");
    assertThat(CryptoApi.text(badAccount)).isEqualTo("{\"erro\":\"contaId deve ser um UUID\"}");
    assertThat(CryptoApi.text(badAsset)).isEqualTo("{\"erro\":\"ativoId deve ser um UUID\"}");
  }
}
