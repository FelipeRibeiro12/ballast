package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.expenseBody;
import static com.felipe.ballast.entries.EntriesApi.incomeBody;
import static com.felipe.ballast.entries.EntriesApi.with;
import static com.felipe.ballast.entries.EntriesApi.without;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class EntryCreationTest {

  private static final String TITLE_ERROR =
      "titulo deve ter entre 1 e 100 caracteres e não pode ter caracteres de controle";
  private static final String DESCRIPTION_ERROR =
      "descricao deve ter no máximo 500 caracteres e não pode ter caracteres de controle";
  private static final String AMOUNT_ERROR =
      "valorCentavos deve ser um inteiro entre 1 e 9007199254740991";
  private static final String TYPE_ERROR = "tipo deve ser receita ou despesa";
  private static final String DATE_ERROR =
      "data deve estar no formato AAAA-MM-DD, com ano entre 1900 e 2199";
  private static final String BAD_REQUEST = "requisição inválida";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private EntriesApi api;
  private EntriesApi.Actor actor;
  private Map<String, Object> valid;

  @BeforeEach
  void setUp() throws Exception {
    api = new EntriesApi(mvc);
    actor = api.newActor();
    valid = expenseBody(actor);
  }

  private long countEntries() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :id")
        .param("id", actor.user().id())
        .query(Long.class)
        .single();
  }

  @Test
  void createsAnExpenseAndAnswers201WithEveryDocumentedField() throws Exception {
    MockHttpServletResponse response = api.create(actor.session(), valid);

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = EntriesApi.body(response);
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder(
            "id",
            "tipo",
            "titulo",
            "descricao",
            "valorCentavos",
            "categoriaId",
            "contaId",
            "dataCompetencia",
            "dataCaixa",
            "status",
            "criadoEm");
    assertThat(UUID.fromString(body.get("id").asString())).isNotNull();
    assertThat(body.get("tipo").asString()).isEqualTo("despesa");
    assertThat(body.get("titulo").asString()).isEqualTo("Mercado do mês");
    assertThat(body.get("descricao").isNull()).isTrue();
    assertThat(body.get("valorCentavos").asLong()).isEqualTo(12_345L);
    assertThat(body.get("categoriaId").asString()).isEqualTo(actor.expenseCategoryId());
    assertThat(body.get("contaId").asString()).isEqualTo(actor.accountId());
    assertThat(body.get("dataCompetencia").asString()).isEqualTo("2026-03-10");
    assertThat(body.get("dataCaixa").asString()).isEqualTo("2026-03-10");
    assertThat(body.get("status").asString()).isEqualTo("realizado");
    assertThat(body.get("criadoEm").asString()).endsWith("Z");
  }

  @Test
  void createsAnIncome() throws Exception {
    MockHttpServletResponse response = api.create(actor.session(), incomeBody(actor));

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(EntriesApi.body(response).get("tipo").asString()).isEqualTo("receita");
  }

  @Test
  void writesTheSameDateInBothDateColumnsAndTheAmountAsExactNumeric() throws Exception {
    JsonNode created = api.createOk(actor.session(), with(valid, "valorCentavos", 1));

    Map<String, Object> row =
        jdbc.sql(
                "SELECT valor, data_competencia, data_caixa, status, usuario_id FROM lancamento"
                    + " WHERE id = :id")
            .param("id", UUID.fromString(created.get("id").asString()))
            .query()
            .singleRow();
    assertThat((BigDecimal) row.get("valor")).isEqualByComparingTo("0.01");
    assertThat(((BigDecimal) row.get("valor")).scale()).isEqualTo(2);
    assertThat(row.get("data_competencia").toString()).isEqualTo("2026-03-10");
    assertThat(row.get("data_caixa").toString()).isEqualTo("2026-03-10");
    assertThat(row.get("status")).isEqualTo("realizado");
    assertThat(row.get("usuario_id")).isEqualTo(actor.user().id());
  }

  @Test
  void acceptsTheLimitsOfTheValidRanges() throws Exception {
    String hundred = "a".repeat(100);
    String fiveHundred = "b".repeat(500);
    long ceiling = 9_007_199_254_740_991L;
    Map<String, Object> body = with(valid, "titulo", hundred);
    body = with(body, "descricao", fiveHundred);
    body = with(body, "valorCentavos", ceiling);
    body = with(body, "data", "1900-01-01");

    JsonNode created = EntriesApi.body(api.create(actor.session(), body));

    assertThat(created.get("valorCentavos").asLong()).isEqualTo(ceiling);
    assertThat(created.get("dataCaixa").asString()).isEqualTo("1900-01-01");
    // Conta própria: a primeira já está no piso do saldo (ADR-024), então não aceita mais despesa.
    Map<String, Object> otherAccount =
        with(
            with(valid, "data", "2199-12-31"),
            "contaId",
            api.newAccount(actor.session(), "Outra " + UUID.randomUUID()));
    assertThat(api.create(actor.session(), otherAccount).getStatus()).isEqualTo(201);
  }

  @Test
  void stripsTheTitleAndTurnsABlankDescriptionIntoNull() throws Exception {
    Map<String, Object> body = with(valid, "titulo", "  Padaria \t");
    body = with(body, "descricao", "   ");

    JsonNode created = EntriesApi.body(api.create(actor.session(), body));

    assertThat(created.get("titulo").asString()).isEqualTo("Padaria");
    assertThat(created.get("descricao").isNull()).isTrue();
  }

  @Test
  void keepsTheDescriptionStripped() throws Exception {
    JsonNode created =
        EntriesApi.body(api.create(actor.session(), with(valid, "descricao", "  nota  ")));

    assertThat(created.get("descricao").asString()).isEqualTo("nota");
  }

  @Test
  void acceptsAnAssignedLetterOutsideTheBmpInTitleAndDescription() throws Exception {
    // U+1D800 tem os 16 bits de baixo em D800, mas é uma letra atribuída e não um surrogate.
    String letter = new String(Character.toChars(0x1D800));

    MockHttpServletResponse response =
        api.create(actor.session(), with(with(valid, "titulo", letter), "descricao", letter + "x"));

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = EntriesApi.body(response);
    assertThat(body.get("titulo").asString()).isEqualTo(letter);
    assertThat(body.get("descricao").asString()).isEqualTo(letter + "x");
  }

  @Test
  void countsCodePointsNotUtf16Units() throws Exception {
    // 100 emojis têm 200 unidades UTF-16 e 100 code points.
    String emojis = "😀".repeat(100);

    assertThat(api.create(actor.session(), with(valid, "titulo", emojis)).getStatus())
        .isEqualTo(201);
    assertThat(api.create(actor.session(), with(valid, "titulo", emojis + "😀")).getStatus())
        .isEqualTo(400);
  }

  static Stream<Arguments> invalidBodies() {
    Map<String, Object> base = new LinkedHashMap<>();
    return Stream.of(
        Arguments.of("valor zero", "valorCentavos", 0, AMOUNT_ERROR),
        Arguments.of("valor negativo", "valorCentavos", -1, AMOUNT_ERROR),
        Arguments.of("valor decimal", "valorCentavos", 12.5, BAD_REQUEST),
        Arguments.of("valor em notação científica", "valorCentavos", "1e3", BAD_REQUEST),
        Arguments.of("valor como texto", "valorCentavos", "100", BAD_REQUEST),
        Arguments.of("valor acima do teto", "valorCentavos", 9_007_199_254_740_992L, AMOUNT_ERROR),
        Arguments.of("valor nulo", "valorCentavos", null, AMOUNT_ERROR),
        Arguments.of("titulo vazio", "titulo", "", TITLE_ERROR),
        Arguments.of("titulo só espaço", "titulo", "   ", TITLE_ERROR),
        Arguments.of("titulo com 101 code points", "titulo", "a".repeat(101), TITLE_ERROR),
        Arguments.of("titulo com quebra de linha", "titulo", "a\nb", TITLE_ERROR),
        Arguments.of("titulo com NUL", "titulo", "a\u0000b", TITLE_ERROR),
        Arguments.of("titulo nulo", "titulo", null, TITLE_ERROR),
        Arguments.of("titulo numérico", "titulo", 5, BAD_REQUEST),
        Arguments.of(
            "descricao com 501 code points", "descricao", "a".repeat(501), DESCRIPTION_ERROR),
        Arguments.of("descricao com controle", "descricao", "a\tb", DESCRIPTION_ERROR),
        Arguments.of("tipo transferencia", "tipo", "transferencia", BAD_REQUEST),
        Arguments.of("tipo em maiúsculas", "tipo", "RECEITA", BAD_REQUEST),
        Arguments.of("tipo nulo", "tipo", null, TYPE_ERROR),
        Arguments.of("tipo numérico", "tipo", 0, BAD_REQUEST),
        Arguments.of("data inexistente", "data", "2026-02-30", DATE_ERROR),
        Arguments.of("data de fevereiro em ano não bissexto", "data", "2026-02-29", DATE_ERROR),
        Arguments.of("ano 1899", "data", "1899-12-31", DATE_ERROR),
        Arguments.of("ano 2200", "data", "2200-01-01", DATE_ERROR),
        Arguments.of("data sem zero à esquerda", "data", "2026-3-5", DATE_ERROR),
        Arguments.of("data compacta", "data", "20260305", DATE_ERROR),
        Arguments.of("data com hora", "data", "2026-03-05T00:00:00", DATE_ERROR),
        Arguments.of("data com espaço", "data", " 2026-03-05", DATE_ERROR),
        Arguments.of("data vazia", "data", "", DATE_ERROR),
        Arguments.of("data numérica", "data", 20260305, BAD_REQUEST),
        Arguments.of("data como lista", "data", java.util.List.of(2026, 3, 5), BAD_REQUEST),
        Arguments.of("data nula", "data", null, DATE_ERROR),
        Arguments.of("contaId nulo", "contaId", null, "contaId é obrigatório"),
        Arguments.of("categoriaId nulo", "categoriaId", null, "categoriaId é obrigatório"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidBodies")
  void rejectsInvalidInputWith400AndSavesNothing(
      String label, String field, Object value, String message) throws Exception {
    MockHttpServletResponse response = api.create(actor.session(), with(valid, field, value));

    assertThat(response.getStatus()).as(label).isEqualTo(400);
    assertThat(EntriesApi.text(response)).as(label).isEqualTo("{\"erro\":\"" + message + "\"}");
    assertThat(countEntries()).isZero();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"tipo", "titulo", "valorCentavos", "categoriaId", "contaId", "data"})
  void aMissingRequiredFieldIs400(String field) throws Exception {
    MockHttpServletResponse response = api.create(actor.session(), without(valid, field));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(countEntries()).isZero();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "status",
        "usuarioId",
        "dataCaixa",
        "dataCompetencia",
        "id",
        "criadoEm",
        "valor",
        "campoQualquer"
      })
  void anUndeclaredFieldIs400AndSavesNothing(String field) throws Exception {
    Map<String, Object> body = with(valid, field, "valor-qualquer");

    MockHttpServletResponse response = api.create(actor.session(), body);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(countEntries()).isZero();
  }

  @Test
  void anExpenseInAnIncomeCategoryIs400() throws Exception {
    MockHttpServletResponse response =
        api.create(actor.session(), with(valid, "categoriaId", actor.incomeCategoryId()));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"categoria não encontrada\"}");
    assertThat(countEntries()).isZero();
  }

  @Test
  void anIncomeInAnExpenseCategoryIs400() throws Exception {
    MockHttpServletResponse response =
        api.create(
            actor.session(), with(incomeBody(actor), "categoriaId", actor.expenseCategoryId()));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"categoria não encontrada\"}");
  }

  @Test
  void aNonexistentOrMalformedCategoryIs400WithTheSameMessage() throws Exception {
    for (String id :
        new String[] {EntriesApi.randomId(), "abc", "1-1-1-1-1", " " + EntriesApi.randomId()}) {
      MockHttpServletResponse response =
          api.create(actor.session(), with(valid, "categoriaId", id));

      assertThat(response.getStatus()).as(id).isEqualTo(400);
      assertThat(EntriesApi.text(response))
          .as(id)
          .isEqualTo("{\"erro\":\"categoria não encontrada\"}");
    }
  }

  @Test
  void aNonexistentOrMalformedAccountIs400WithTheSameMessage() throws Exception {
    for (String id :
        new String[] {EntriesApi.randomId(), "abc", "1-1-1-1-1", " " + EntriesApi.randomId()}) {
      MockHttpServletResponse response = api.create(actor.session(), with(valid, "contaId", id));

      assertThat(response.getStatus()).as(id).isEqualTo(400);
      assertThat(EntriesApi.text(response)).as(id).isEqualTo("{\"erro\":\"conta não encontrada\"}");
    }
    assertThat(countEntries()).isZero();
  }

  @Test
  void anArchivedAccountIs400() throws Exception {
    api.setAccountArchived(actor.session(), actor.accountId(), true);

    MockHttpServletResponse response = api.create(actor.session(), valid);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"conta arquivada\"}");
    assertThat(countEntries()).isZero();
  }

  @Test
  void theCreatedEntryCanBeReadBack() throws Exception {
    JsonNode created = api.createOk(actor.session(), valid);

    MockHttpServletResponse response = api.find(actor.session(), created.get("id").asString());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(EntriesApi.body(response)).isEqualTo(created);
    assertThat(LocalDate.parse(EntriesApi.body(response).get("dataCaixa").asString()))
        .isEqualTo(LocalDate.of(2026, 3, 10));
  }
}
