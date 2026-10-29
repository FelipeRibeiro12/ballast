package com.felipe.ballast.imports;

import static com.felipe.ballast.imports.ImportsApi.BR_PREVIEW_QUERY;
import static com.felipe.ballast.imports.ImportsApi.body;
import static com.felipe.ballast.imports.ImportsApi.brMapping;
import static com.felipe.ballast.imports.ImportsApi.csv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class CsvPreviewTest {

  private static final String HEADER = "Data;Valor;Descricao;Extra";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private ImportsApi api;
  private ImportsApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new ImportsApi(mvc);
    actor = api.newActor();
  }

  private MockHttpServletResponse preview(String query, byte[] content) throws Exception {
    return api.previewCsv(actor.session(), query, content);
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE usuario_id = :u")
        .param("u", actor.user().id())
        .query(Long.class)
        .single();
  }

  @Test
  void showsTheFirstTenRowsWithCellsAndInterpretation() throws Exception {
    StringBuilder text = new StringBuilder(HEADER).append('\n');
    for (int i = 1; i <= 12; i++) {
      text.append(String.format("%02d/10/2026;-%d,50;LOJA %d;x%n", i, i, i));
    }

    MockHttpServletResponse response =
        preview(BR_PREVIEW_QUERY, text.toString().getBytes(StandardCharsets.UTF_8));

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode result = body(response);
    assertThat(result.get("totalLinhas").asInt()).isEqualTo(12);
    assertThat(result.get("colunasDetectadas").asInt()).isEqualTo(4);
    assertThat(result.get("linhas")).hasSize(10);
    JsonNode first = result.get("linhas").get(0);
    assertThat(first.get("ordem").asInt()).isEqualTo(1);
    assertThat(first.get("celulas")).hasSize(4);
    assertThat(first.get("celulas").get(2).asString()).isEqualTo("LOJA 1");
    assertThat(first.get("valida").asBoolean()).isTrue();
    assertThat(first.get("data").asString()).isEqualTo("2026-10-01");
    assertThat(first.get("tipo").asString()).isEqualTo("despesa");
    assertThat(first.get("valorCentavos").asLong()).isEqualTo(150);
    assertThat(first.get("titulo").asString()).isEqualTo("LOJA 1");
    assertThat(first.get("erro").isNull()).isTrue();
  }

  @Test
  void showsWhyARowIsInvalid() throws Exception {
    MockHttpServletResponse response =
        preview(
            BR_PREVIEW_QUERY,
            csv(HEADER, "31/02/2026;-1,00;A;x", "03/10/2026;abc;B;x", "03/10/2026;-1,00"));

    JsonNode rows = body(response).get("linhas");
    assertThat(rows.get(0).get("valida").asBoolean()).isFalse();
    assertThat(rows.get(0).get("erro").asString()).isEqualTo("data inválida");
    assertThat(rows.get(0).get("data").isNull()).isTrue();
    assertThat(rows.get(0).get("valorCentavos").isNull()).isTrue();
    assertThat(rows.get(1).get("erro").asString()).isEqualTo("valor inválido");
    assertThat(rows.get(2).get("erro").asString())
        .isEqualTo("linha com menos colunas que o mapeamento");
  }

  @Test
  void showsFormulaCellsAlreadyNeutralized() throws Exception {
    MockHttpServletResponse response =
        preview(BR_PREVIEW_QUERY, csv(HEADER, "03/10/2026;-1,00;=CMD();@x"));

    JsonNode row = body(response).get("linhas").get(0);
    assertThat(row.get("celulas").get(2).asString()).isEqualTo("'=CMD()");
    assertThat(row.get("celulas").get(3).asString()).isEqualTo("'@x");
    assertThat(row.get("titulo").asString()).isEqualTo("'=CMD()");
  }

  @Test
  void persistsNothingAndDoesNotUseAPendingSlot() throws Exception {
    String mappingId = api.createMappingOk(actor.session(), brMapping("Banco"));
    for (int i = 0; i < 20; i++) {
      api.uploadCsvOk(
          actor.session(), actor.accountId(), mappingId, csv("D;V;X", "03/10/2026;-1,00;L" + i));
    }
    long linesBefore = count("importacao_linha");

    MockHttpServletResponse response =
        preview(BR_PREVIEW_QUERY, csv(HEADER, "03/10/2026;-1,00;NOVA;x"));

    // Com 20 pendentes o envio recusaria; a prévia segue funcionando e não grava.
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(count("importacao")).isEqualTo(20);
    assertThat(count("importacao_linha")).isEqualTo(linesBefore);
    assertThat(count("lancamento")).isZero();
  }

  @Test
  void idColumnIsOptional() throws Exception {
    MockHttpServletResponse response =
        preview(BR_PREVIEW_QUERY + "&coluna_id=3", csv(HEADER, "03/10/2026;-1,00;A;ID1"));

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void validatesTheParametersAtTheEdge() throws Exception {
    byte[] file = csv(HEADER, "03/10/2026;-1,00;A;x");
    String[] bad = {
      "",
      BR_PREVIEW_QUERY.replace("separador=ponto_e_virgula", "separador=%3B"),
      BR_PREVIEW_QUERY.replace("separador=ponto_e_virgula", "separador=tab&separador=virgula"),
      BR_PREVIEW_QUERY.replace("dd/MM/yyyy", "dd.MM.yyyy"),
      BR_PREVIEW_QUERY.replace("decimal=virgula", "decimal=x"),
      BR_PREVIEW_QUERY.replace("sinal=negativo_despesa", "sinal=x"),
      BR_PREVIEW_QUERY.replace("tem_cabecalho=true", "tem_cabecalho=1"),
      BR_PREVIEW_QUERY.replace("coluna_data=0", "coluna_data=-1"),
      BR_PREVIEW_QUERY.replace("coluna_data=0", "coluna_data=50"),
      BR_PREVIEW_QUERY.replace("coluna_data=0", "coluna_data=abc"),
      BR_PREVIEW_QUERY.replace("coluna_valor=1", "coluna_valor=0"),
      BR_PREVIEW_QUERY + "&coluna_id=2",
      BR_PREVIEW_QUERY + "&coluna_id=50",
      BR_PREVIEW_QUERY + "&outro=1",
      BR_PREVIEW_QUERY.replace("&coluna_descricao=2", "")
    };
    for (String query : bad) {
      assertThat(preview(query, file).getStatus()).as(query).isEqualTo(400);
    }
  }

  @Test
  void keepsTheSameFileRules() throws Exception {
    assertThat(preview(BR_PREVIEW_QUERY, new byte[(1 << 20) + 1]).getStatus()).isEqualTo(413);
    assertThat(preview(BR_PREVIEW_QUERY, csv(HEADER, "03/10/2026;-1,00;A\0")).getStatus())
        .isEqualTo(422);
    assertThat(preview(BR_PREVIEW_QUERY, new byte[0]).getStatus()).isEqualTo(422);
    StringBuilder many = new StringBuilder(HEADER).append('\n');
    for (int i = 0; i < 2001; i++) {
      many.append("03/10/2026;-1,00;A;x\n");
    }
    assertThat(
            preview(BR_PREVIEW_QUERY, many.toString().getBytes(StandardCharsets.UTF_8)).getStatus())
        .isEqualTo(422);
    MockHttpServletResponse json =
        mvc.perform(
                post("/importacoes/csv/previa?" + BR_PREVIEW_QUERY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .cookie(actor.session()))
            .andReturn()
            .getResponse();
    assertThat(json.getStatus()).isEqualTo(415);
  }

  @Test
  void requiresALogin() throws Exception {
    assertThat(
            api.previewCsv(null, BR_PREVIEW_QUERY, csv(HEADER, "03/10/2026;-1,00;A;x")).getStatus())
        .isEqualTo(401);
  }
}
