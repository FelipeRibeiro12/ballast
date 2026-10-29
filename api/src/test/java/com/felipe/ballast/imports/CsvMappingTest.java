package com.felipe.ballast.imports;

import static com.felipe.ballast.imports.ImportsApi.body;
import static com.felipe.ballast.imports.ImportsApi.brMapping;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class CsvMappingTest {

  @Autowired MockMvc mvc;
  private ImportsApi api;
  private ImportsApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new ImportsApi(mvc);
    actor = api.newActor();
  }

  @Test
  void createsListsUpdatesAndDeletes() throws Exception {
    Map<String, Object> mapping = brMapping("Banco X");
    mapping.put("colunaId", 3);

    MockHttpServletResponse created = api.createMapping(actor.session(), mapping);

    assertThat(created.getStatus()).isEqualTo(201);
    JsonNode saved = body(created);
    assertThat(saved.get("nome").asString()).isEqualTo("Banco X");
    assertThat(saved.get("separador").asString()).isEqualTo("ponto_e_virgula");
    assertThat(saved.get("formatoData").asString()).isEqualTo("dd/MM/yyyy");
    assertThat(saved.get("decimal").asString()).isEqualTo("virgula");
    assertThat(saved.get("sinal").asString()).isEqualTo("negativo_despesa");
    assertThat(saved.get("temCabecalho").asBoolean()).isTrue();
    assertThat(saved.get("colunaData").asInt()).isZero();
    assertThat(saved.get("colunaValor").asInt()).isEqualTo(1);
    assertThat(saved.get("colunaDescricao").asInt()).isEqualTo(2);
    assertThat(saved.get("colunaId").asInt()).isEqualTo(3);
    String id = saved.get("id").asString();

    JsonNode listed = body(api.listMappings(actor.session()));
    assertThat(listed).hasSize(1);
    assertThat(listed.get(0).get("id").asString()).isEqualTo(id);

    Map<String, Object> changed = brMapping("Banco X novo");
    changed.put("separador", "tab");
    changed.put("sinal", "positivo_despesa");
    changed.put("temCabecalho", false);
    MockHttpServletResponse updated = api.updateMapping(actor.session(), id, changed);
    assertThat(updated.getStatus()).isEqualTo(200);
    assertThat(body(updated).get("separador").asString()).isEqualTo("tab");
    assertThat(body(updated).get("colunaId").isNull()).isTrue();
    assertThat(body(api.listMappings(actor.session())).get(0).get("nome").asString())
        .isEqualTo("Banco X novo");

    assertThat(api.deleteMapping(actor.session(), id).getStatus()).isEqualTo(204);
    assertThat(body(api.listMappings(actor.session()))).isEmpty();
    assertThat(api.deleteMapping(actor.session(), id).getStatus()).isEqualTo(404);
  }

  @Test
  void nameIsTrimmedAndUniquePerUser() throws Exception {
    api.createMappingOk(actor.session(), brMapping("  Banco X  "));

    assertThat(body(api.listMappings(actor.session())).get(0).get("nome").asString())
        .isEqualTo("Banco X");
    MockHttpServletResponse duplicate = api.createMapping(actor.session(), brMapping("Banco X"));
    assertThat(duplicate.getStatus()).isEqualTo(409);
    assertThat(body(duplicate).get("erro").asString())
        .isEqualTo("já existe um mapeamento com esse nome");

    // Outro usuário pode usar o mesmo nome.
    ImportsApi.Actor other = api.newActor();
    assertThat(api.createMapping(other.session(), brMapping("Banco X")).getStatus()).isEqualTo(201);
  }

  @Test
  void renamingToAnExistingNameIsAConflictAndKeepingTheOwnNameIsFine() throws Exception {
    api.createMappingOk(actor.session(), brMapping("A"));
    String second = api.createMappingOk(actor.session(), brMapping("B"));

    assertThat(api.updateMapping(actor.session(), second, brMapping("A")).getStatus())
        .isEqualTo(409);
    assertThat(api.updateMapping(actor.session(), second, brMapping("B")).getStatus())
        .isEqualTo(200);
  }

  @Test
  void stopsAtTwentyMappingsPerUser() throws Exception {
    for (int i = 0; i < 20; i++) {
      api.createMappingOk(actor.session(), brMapping("Banco " + i));
    }

    MockHttpServletResponse response = api.createMapping(actor.session(), brMapping("Banco 20"));

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(body(response).get("erro").asString())
        .isEqualTo("limite de mapeamentos de CSV atingido");
    // Outro usuário não é afetado.
    assertThat(api.createMapping(api.newActor().session(), brMapping("Banco 0")).getStatus())
        .isEqualTo(201);
  }

  private void assertRefused(Map<String, Object> mapping) throws Exception {
    MockHttpServletResponse response = api.createMapping(actor.session(), mapping);
    assertThat(response.getStatus()).as(mapping.toString()).isEqualTo(400);
    assertThat(body(response).get("erro").asString()).isNotBlank();
  }

  @Test
  void validatesEveryFieldAtTheEdge() throws Exception {
    Map<String, Object> blank = brMapping(" ");
    Map<String, Object> longName = brMapping("x".repeat(61));
    Map<String, Object> control = brMapping("Ban\u0007co");
    Map<String, Object> noName = brMapping("A");
    noName.remove("nome");
    Map<String, Object> badSeparator = brMapping("A");
    badSeparator.put("separador", ";");
    Map<String, Object> badFormat = brMapping("A");
    badFormat.put("formatoData", "dd.MM.yyyy");
    Map<String, Object> badDecimal = brMapping("A");
    badDecimal.put("decimal", "virgulas");
    Map<String, Object> badSign = brMapping("A");
    badSign.put("sinal", "negativo");
    Map<String, Object> noHeader = brMapping("A");
    noHeader.remove("temCabecalho");
    Map<String, Object> stringHeader = brMapping("A");
    stringHeader.put("temCabecalho", "true");
    Map<String, Object> negativeColumn = brMapping("A");
    negativeColumn.put("colunaData", -1);
    Map<String, Object> highColumn = brMapping("A");
    highColumn.put("colunaValor", 50);
    Map<String, Object> highId = brMapping("A");
    highId.put("colunaId", 50);
    Map<String, Object> fractionColumn = brMapping("A");
    fractionColumn.put("colunaData", 1.5);
    Map<String, Object> sameColumns = brMapping("A");
    sameColumns.put("colunaValor", 0);
    Map<String, Object> sameIdColumn = brMapping("A");
    sameIdColumn.put("colunaId", 2);
    Map<String, Object> unknownField = brMapping("A");
    unknownField.put("usuarioId", UUID.randomUUID().toString());

    for (Map<String, Object> mapping :
        java.util.List.of(
            blank,
            longName,
            control,
            noName,
            badSeparator,
            badFormat,
            badDecimal,
            badSign,
            noHeader,
            stringHeader,
            negativeColumn,
            highColumn,
            highId,
            fractionColumn,
            sameColumns,
            sameIdColumn,
            unknownField)) {
      assertRefused(mapping);
    }
    assertThat(body(api.listMappings(actor.session()))).isEmpty();
  }

  @Test
  void acceptsTheBoundaries() throws Exception {
    Map<String, Object> edge = brMapping("x".repeat(60));
    edge.put("colunaData", 49);
    edge.put("colunaValor", 0);
    edge.put("colunaDescricao", 48);
    edge.put("colunaId", 47);

    assertThat(api.createMapping(actor.session(), edge).getStatus()).isEqualTo(201);
  }

  @Test
  void aMalformedIdIsNotFound() throws Exception {
    assertThat(api.updateMapping(actor.session(), "nao-e-uuid", brMapping("A")).getStatus())
        .isEqualTo(404);
    assertThat(api.deleteMapping(actor.session(), "nao-e-uuid").getStatus()).isEqualTo(404);
  }
}
