package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AccountCreationTest {

  private static final String MAX_SAFE = "9007199254740991";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private AccountsApi api;
  private TestUsers users;
  private Cookie session;

  @BeforeEach
  void setUp() throws Exception {
    api = new AccountsApi(mvc);
    users = new TestUsers(mvc);
    session = users.session(users.register());
  }

  private static String account(String name, String type, String balance) {
    String balanceField = balance == null ? "" : ",\"saldoInicialCentavos\":" + balance;
    return "{\"nome\":\"" + name + "\",\"tipo\":\"" + type + "\"" + balanceField + "}";
  }

  @Test
  void createsAnAccountAndReadsItBack() throws Exception {
    MockHttpServletResponse created = api.create(session, account("Nubank", "corrente", "250000"));

    assertThat(created.getStatus()).isEqualTo(201);
    JsonNode body = AccountsApi.body(created);
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder(
            "id",
            "nome",
            "tipo",
            "moeda",
            "saldoInicialCentavos",
            "saldoCentavos",
            "arquivada",
            "criadaEm",
            "cartao",
            "categoriasPermitidas",
            "percentualCdi",
            "emInvestimentos");
    assertThat(body.get("cartao").isNull()).isTrue();
    assertThat(body.get("categoriasPermitidas").isNull()).isTrue();
    assertThat(body.get("nome").asString()).isEqualTo("Nubank");
    assertThat(body.get("tipo").asString()).isEqualTo("corrente");
    assertThat(body.get("moeda").asString()).isEqualTo("BRL");
    assertThat(body.get("saldoInicialCentavos").asLong()).isEqualTo(250000L);
    assertThat(body.get("saldoCentavos").asLong()).isEqualTo(250000L);
    assertThat(body.get("arquivada").asBoolean()).isFalse();
    assertThat(body.get("criadaEm").isString()).isTrue();

    MockHttpServletResponse read = api.find(session, body.get("id").asString());
    assertThat(read.getStatus()).isEqualTo(200);
    assertThat(AccountsApi.body(read)).isEqualTo(body);
  }

  @Test
  void storesCentsAsTwoDecimalPlacesAndReturnsTheSameInteger() throws Exception {
    JsonNode body = AccountsApi.body(api.create(session, account("Carteira", "dinheiro", "12345")));

    BigDecimal stored =
        jdbc.sql("SELECT saldo_inicial FROM conta WHERE id = :id")
            .param("id", UUID.fromString(body.get("id").asString()))
            .query(BigDecimal.class)
            .single();

    assertThat(stored).isEqualByComparingTo("123.45");
    assertThat(stored.scale()).isEqualTo(2);
    assertThat(body.get("saldoInicialCentavos").asLong()).isEqualTo(12345L);
    assertThat(body.get("saldoCentavos").asLong()).isEqualTo(12345L);
  }

  @Test
  void defaultsTheInitialBalanceToZero() throws Exception {
    MockHttpServletResponse response = api.create(session, account("Poupança", "poupanca", null));

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = AccountsApi.body(response);
    assertThat(body.get("saldoInicialCentavos").asLong()).isZero();
    assertThat(body.get("saldoCentavos").asLong()).isZero();
  }

  @Test
  void acceptsANegativeBalance() throws Exception {
    JsonNode body =
        AccountsApi.body(api.create(session, account("Cheque especial", "corrente", "-50075")));

    assertThat(body.get("saldoInicialCentavos").asLong()).isEqualTo(-50075L);
    assertThat(body.get("saldoCentavos").asLong()).isEqualTo(-50075L);
  }

  @Test
  void acceptsTheLimitsAndRoundTripsThemExactly() throws Exception {
    JsonNode max = AccountsApi.body(api.create(session, account("Teto", "corrente", MAX_SAFE)));
    JsonNode min =
        AccountsApi.body(api.create(session, account("Piso", "corrente", "-" + MAX_SAFE)));

    assertThat(max.get("saldoCentavos").asLong()).isEqualTo(9007199254740991L);
    assertThat(min.get("saldoCentavos").asLong()).isEqualTo(-9007199254740991L);
  }

  @ParameterizedTest
  @ValueSource(strings = {"9007199254740992", "-9007199254740992", "99999999999999999999"})
  void rejectsABalanceBeyondTheLimit(String balance) throws Exception {
    MockHttpServletResponse response = api.create(session, account("Grande", "corrente", balance));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .doesNotContain(balance)
        .contains("\"erro\"");
  }

  @ParameterizedTest
  @ValueSource(strings = {"12.5", "\"100\"", "1e3", "null"})
  void rejectsABalanceThatIsNotAnInteger(String balance) throws Exception {
    MockHttpServletResponse response = api.create(session, account("Decimal", "corrente", balance));

    // null equivale a campo ausente e usa o padrão 0; o resto é 400.
    assertThat(response.getStatus()).isEqualTo(balance.equals("null") ? 201 : 400);
  }

  @Test
  void rejectsADuplicateNameIgnoringCaseWith409() throws Exception {
    api.createOk(session, "Nubank");

    MockHttpServletResponse response = api.create(session, account("nUBANK", "poupanca", null));

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"já existe uma conta com esse nome\"}");
  }

  @Test
  void aDuplicateNameDoesNotLeakTheNameToTheLog(CapturedOutput output) throws Exception {
    api.createOk(session, "Conta Secreta Zeta");

    api.create(session, account("conta secreta zeta", "corrente", null));

    assertThat(output.getAll().toLowerCase()).doesNotContain("conta secreta zeta");
  }

  @Test
  void aDuplicateNameStillCountsWhenTheAccountIsArchived() throws Exception {
    JsonNode first = api.createOk(session, "Antiga");
    api.setArchived(session, first.get("id").asString(), true);

    assertThat(api.create(session, account("antiga", "corrente", null)).getStatus()).isEqualTo(409);
  }

  @Test
  void theSameNameInDifferentUsersIsAllowed() throws Exception {
    Cookie other = users.session(users.register());
    api.createOk(session, "Nubank");

    assertThat(api.create(other, account("Nubank", "corrente", null)).getStatus()).isEqualTo(201);
  }

  @Test
  void stripsSpacesAroundTheName() throws Exception {
    JsonNode body = AccountsApi.body(api.create(session, account("  Itaú  ", "corrente", null)));

    assertThat(body.get("nome").asString()).isEqualTo("Itaú");
    String stored =
        jdbc.sql("SELECT nome FROM conta WHERE id = :id")
            .param("id", UUID.fromString(body.get("id").asString()))
            .query(String.class)
            .single();
    assertThat(stored).isEqualTo("Itaú");
  }

  static Stream<String> invalidNames() {
    return Stream.of(
        "   ",
        "",
        "a".repeat(101),
        "com\\u0000nulo",
        "com\\ttab",
        "quebra\\nlinha",
        "sep\\u2028linha");
  }

  @ParameterizedTest
  @MethodSource("invalidNames")
  void rejectsAnInvalidName(String name) throws Exception {
    MockHttpServletResponse response = api.create(session, account(name, "corrente", null));

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void acceptsANameWithExactly100Characters() throws Exception {
    assertThat(api.create(session, account("a".repeat(100), "corrente", null)).getStatus())
        .isEqualTo(201);
  }

  @Test
  void rejectsAMissingName() throws Exception {
    assertThat(api.create(session, "{\"tipo\":\"corrente\"}").getStatus()).isEqualTo(400);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"cartao_credito", "Beneficio", "Cripto", "previdencia", "Corrente", "", "0"})
  void rejectsATypeOutsideTheList(String type) throws Exception {
    MockHttpServletResponse response = api.create(session, account("Conta", type, null));

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void rejectsANumericOrMissingType() throws Exception {
    assertThat(api.create(session, "{\"nome\":\"X\",\"tipo\":0}").getStatus()).isEqualTo(400);
    assertThat(api.create(session, "{\"nome\":\"X\"}").getStatus()).isEqualTo(400);
  }

  @ParameterizedTest
  @ValueSource(strings = {"moeda", "usuarioId", "id", "arquivada", "saldoCentavos"})
  void rejectsAnUndeclaredField(String field) throws Exception {
    MockHttpServletResponse response =
        api.create(
            session, "{\"nome\":\"Conta\",\"tipo\":\"corrente\",\"" + field + "\":\"qualquer\"}");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(
            jdbc.sql("SELECT count(*) FROM conta WHERE nome = 'Conta'").query(Long.class).single())
        .isZero();
  }

  @Test
  void rejectsABodyAbove4KiBWith413() throws Exception {
    String padded = account("Grande", "corrente", null) + " ".repeat(4096);

    MockHttpServletResponse response = api.create(session, padded);

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"corpo grande demais\"}");
  }

  @Test
  void rejectsAContentTypeOtherThanJson() throws Exception {
    var request =
        post("/contas")
            .contentType(MediaType.TEXT_PLAIN)
            .content(account("Texto", "corrente", null))
            .cookie(session);

    assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(415);
  }

  @Test
  void requiresASession() throws Exception {
    MockHttpServletResponse response = api.create(null, account("Anonima", "corrente", null));

    assertThat(response.getStatus()).isEqualTo(401);
  }
}
