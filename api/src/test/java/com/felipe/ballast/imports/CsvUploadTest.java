package com.felipe.ballast.imports;

import static com.felipe.ballast.imports.ImportsApi.body;
import static com.felipe.ballast.imports.ImportsApi.brMapping;
import static com.felipe.ballast.imports.ImportsApi.csv;
import static com.felipe.ballast.imports.ImportsApi.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class CsvUploadTest {

  private static final String HEADER = "Data;Valor;Descricao";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private ImportsApi api;
  private ImportsApi.Actor actor;
  private String mappingId;

  @BeforeEach
  void setUp() throws Exception {
    api = new ImportsApi(mvc);
    actor = api.newActor();
    mappingId = api.createMappingOk(actor.session(), brMapping("Banco X"));
  }

  private JsonNode upload(byte[] content) throws Exception {
    return api.uploadCsvOk(actor.session(), actor.accountId(), mappingId, content);
  }

  private MockHttpServletResponse tryUpload(byte[] content) throws Exception {
    return api.uploadCsv(actor.session(), actor.accountId(), mappingId, content);
  }

  private JsonNode confirmed(String importId) throws Exception {
    MockHttpServletResponse response = api.confirm(actor.session(), importId, null);
    assertThat(response.getStatus()).isEqualTo(200);
    return body(response);
  }

  private long entryCount() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :u")
        .param("u", actor.user().id())
        .query(Long.class)
        .single();
  }

  private JsonNode entries() throws Exception {
    return body(api.listEntries(actor.session(), "2026-01-01", "2026-12-31"));
  }

  @Test
  void createsAPendingImportFromABrazilianCsv() throws Exception {
    JsonNode detail =
        upload(
            csv(
                HEADER,
                "03/10/2026;-1.234,56;ALUGUEL",
                "05/10/2026;1.500,00;SALARIO",
                "06/10/2026;12;X"));

    assertThat(detail.get("status").asString()).isEqualTo("pendente");
    assertThat(detail.get("contaId").asString()).isEqualTo(actor.accountId());
    assertThat(detail.get("totalLinhas").asInt()).isEqualTo(3);
    JsonNode rent = line(detail, 1);
    assertThat(rent.get("data").asString()).isEqualTo("2026-10-03");
    assertThat(rent.get("tipo").asString()).isEqualTo("despesa");
    assertThat(rent.get("valorCentavos").asLong()).isEqualTo(123_456);
    assertThat(rent.get("titulo").asString()).isEqualTo("ALUGUEL");
    assertThat(rent.get("proposta").asString()).isEqualTo("importar");
    assertThat(line(detail, 2).get("tipo").asString()).isEqualTo("receita");
    assertThat(line(detail, 2).get("valorCentavos").asLong()).isEqualTo(150_000);
    assertThat(line(detail, 3).get("valorCentavos").asLong()).isEqualTo(1_200);
    assertThat(entryCount()).isZero();
  }

  @Test
  void readsADotDecimalCommaSeparatedIsoDateMapping() throws Exception {
    Map<String, Object> mapping = brMapping("Em inglês");
    mapping.put("separador", "virgula");
    mapping.put("formatoData", "yyyy-MM-dd");
    mapping.put("decimal", "ponto");
    String id = api.createMappingOk(actor.session(), mapping);

    JsonNode detail =
        api.uploadCsvOk(
            actor.session(),
            actor.accountId(),
            id,
            csv("date,amount,desc", "2026-10-03,\"-1,234.56\",RENT", "2026-10-04,10.5,PIX"));

    assertThat(line(detail, 1).get("valorCentavos").asLong()).isEqualTo(123_456);
    assertThat(line(detail, 1).get("data").asString()).isEqualTo("2026-10-03");
    assertThat(line(detail, 2).get("valorCentavos").asLong()).isEqualTo(1_050);
    assertThat(line(detail, 2).get("tipo").asString()).isEqualTo("receita");
  }

  @Test
  void impossibleDateAndUnreadableAmountAreInvalidAndStayOutOfTheConfirmation() throws Exception {
    JsonNode detail =
        upload(
            csv(
                HEADER,
                "31/02/2026;-1,00;DATA RUIM",
                "03/10/2026;12,345;CASAS",
                "03/10/2026;(5,00);PARENTESES",
                "03/10/2026;-0,00;ZERO",
                "03/10/2026;-2,00;BOA"));

    assertThat(detail.get("totalInvalidas").asInt()).isEqualTo(4);
    JsonNode badDate = line(detail, 1);
    assertThat(badDate.get("valida").asBoolean()).isFalse();
    assertThat(badDate.get("erro").asString()).isEqualTo("data inválida");
    assertThat(badDate.get("proposta").asString()).isEqualTo("ignorar");
    assertThat(badDate.get("motivo").asString()).isEqualTo("invalida");
    assertThat(line(detail, 2).get("erro").asString())
        .isEqualTo("valor com mais de 2 casas decimais");
    assertThat(line(detail, 3).get("erro").asString()).isEqualTo("valor inválido");
    assertThat(line(detail, 4).get("erro").asString()).isEqualTo("valor zero");

    JsonNode result = confirmed(detail.get("id").asString());
    assertThat(result.get("criadas").asInt()).isEqualTo(1);
    assertThat(result.get("ignoradas").asInt()).isEqualTo(4);
    assertThat(entryCount()).isEqualTo(1);
  }

  @Test
  void aShortRowIsInvalidWithoutDroppingTheFile() throws Exception {
    JsonNode detail = upload(csv(HEADER, "03/10/2026;-1,00", "04/10/2026;-2,00;B"));

    assertThat(detail.get("totalLinhas").asInt()).isEqualTo(2);
    assertThat(line(detail, 1).get("valida").asBoolean()).isFalse();
    assertThat(line(detail, 1).get("erro").asString())
        .isEqualTo("linha com menos colunas que o mapeamento");
    assertThat(line(detail, 2).get("valida").asBoolean()).isTrue();
  }

  @Test
  void theSameFileTwiceIsRefusedAndDiscardingFreesIt() throws Exception {
    byte[] file = csv(HEADER, "03/10/2026;-1,00;A");
    String first = upload(file).get("id").asString();

    MockHttpServletResponse second = tryUpload(file);

    assertThat(second.getStatus()).isEqualTo(409);
    assertThat(body(second).get("importacaoId").asString()).isEqualTo(first);
    assertThat(api.discard(actor.session(), first).getStatus()).isEqualTo(204);
    assertThat(tryUpload(file).getStatus()).isEqualTo(201);
  }

  @Test
  void aResentStatementIsRecognizedLineByLineAndCreatesNothing() throws Exception {
    byte[] file = csv(HEADER, "03/10/2026;-1,00;A", "04/10/2026;-2,00;B");
    confirmed(upload(file).get("id").asString());
    assertThat(entryCount()).isEqualTo(2);
    // Mesmo conteúdo com uma linha em branco no fim: outro hash de arquivo, as mesmas transações.
    byte[] again = csv(HEADER, "03/10/2026;-1,00;A", "04/10/2026;-2,00;B", "");

    assertThat(tryUpload(file).getStatus()).isEqualTo(409);
    JsonNode detail = upload(again);

    for (int order = 1; order <= 2; order++) {
      assertThat(line(detail, order).get("proposta").asString()).isEqualTo("ignorar");
      assertThat(line(detail, order).get("motivo").asString()).isEqualTo("ja_importada");
    }
    JsonNode result = confirmed(detail.get("id").asString());
    assertThat(result.get("criadas").asInt()).isZero();
    assertThat(entryCount()).isEqualTo(2);
  }

  @Test
  void anOverlappingFileFromAnotherDayOnlyImportsTheNewLines() throws Exception {
    confirmed(
        upload(csv(HEADER, "01/10/2026;-1,00;A", "02/10/2026;-2,00;B", "03/10/2026;-3,00;C"))
            .get("id")
            .asString());

    JsonNode second =
        upload(csv(HEADER, "02/10/2026;-2,00;B", "03/10/2026;-3,00;C", "04/10/2026;-4,00;D"));

    assertThat(line(second, 1).get("motivo").asString()).isEqualTo("ja_importada");
    assertThat(line(second, 2).get("motivo").asString()).isEqualTo("ja_importada");
    assertThat(line(second, 3).get("proposta").asString()).isEqualTo("importar");
    JsonNode result = confirmed(second.get("id").asString());
    assertThat(result.get("criadas").asInt()).isEqualTo(1);
    assertThat(entryCount()).isEqualTo(4);
  }

  @Test
  void twoIdenticalCoffeesOnTheSameDayAreTwoEntries() throws Exception {
    JsonNode detail = upload(csv(HEADER, "03/10/2026;-8,00;CAFE", "03/10/2026;-8,00;CAFE"));

    assertThat(line(detail, 1).get("proposta").asString()).isEqualTo("importar");
    assertThat(line(detail, 2).get("proposta").asString()).isEqualTo("importar");
    assertThat(confirmed(detail.get("id").asString()).get("criadas").asInt()).isEqualTo(2);
    assertThat(entryCount()).isEqualTo(2);
  }

  @Test
  void anOverlappingStatementWithThreeCoffeesRecognizesTwoAndImportsOne() throws Exception {
    confirmed(
        upload(csv(HEADER, "03/10/2026;-8,00;CAFE", "03/10/2026;-8,00;CAFE")).get("id").asString());

    JsonNode detail =
        upload(
            csv(HEADER, "03/10/2026;-8,00;CAFE", "03/10/2026;-8,00;CAFE", "03/10/2026;-8,00;CAFE"));

    assertThat(line(detail, 1).get("motivo").asString()).isEqualTo("ja_importada");
    assertThat(line(detail, 2).get("motivo").asString()).isEqualTo("ja_importada");
    assertThat(line(detail, 3).get("proposta").asString()).isEqualTo("importar");
    assertThat(confirmed(detail.get("id").asString()).get("criadas").asInt()).isEqualTo(1);
    assertThat(entryCount()).isEqualTo(3);
  }

  @Test
  void formulasGetAnApostropheInTheTitleTheRawLineAndTheConfirmedEntry() throws Exception {
    JsonNode detail =
        upload(
            csv(
                HEADER,
                "03/10/2026;-1,00;\"=HYPERLINK(\"\"http://x\"\",\"\"a\"\")\"",
                "03/10/2026;-2,00;+1",
                "03/10/2026;-3,00;-2+3",
                "03/10/2026;-4,00;@SUM(A1)",
                "03/10/2026;-5,00;\"\tcmd\"",
                "03/10/2026;-6,00;PADARIA"));

    for (int order = 1; order <= 5; order++) {
      assertThat(line(detail, order).get("titulo").asString()).startsWith("'");
    }
    assertThat(line(detail, 1).get("titulo").asString()).startsWith("'=HYPERLINK(");
    assertThat(line(detail, 6).get("titulo").asString()).isEqualTo("PADARIA");
    String importId = detail.get("id").asString();
    // A célula crua guardada no banco também chega neutralizada.
    String rawCell =
        jdbc.sql(
                "SELECT bruta ->> 'c3' FROM importacao_linha WHERE importacao_id = :id"
                    + " AND ordem = 1")
            .param("id", UUID.fromString(importId))
            .query(String.class)
            .single();
    assertThat(rawCell).startsWith("'=HYPERLINK(");
    String rawAmount =
        jdbc.sql(
                "SELECT bruta ->> 'c2' FROM importacao_linha WHERE importacao_id = :id"
                    + " AND ordem = 3")
            .param("id", UUID.fromString(importId))
            .query(String.class)
            .single();
    assertThat(rawAmount).isEqualTo("'-3,00");

    confirmed(importId);
    long raw =
        jdbc.sql(
                "SELECT count(*) FROM lancamento WHERE usuario_id = :u"
                    + " AND (titulo ~ '^[=+@-]' OR descricao ~ '^[=+@-]')")
            .param("u", actor.user().id())
            .query(Long.class)
            .single();
    assertThat(raw).isZero();
    long neutralized =
        jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :u AND titulo LIKE E'''%'")
            .param("u", actor.user().id())
            .query(Long.class)
            .single();
    assertThat(neutralized).isEqualTo(5);
  }

  @Test
  void aHugeCellIsCutAndTheTitleKeepsTheDatabaseLimits() throws Exception {
    JsonNode detail = upload(csv(HEADER, "03/10/2026;-1,00;" + "Z".repeat(100_000)));

    JsonNode first = line(detail, 1);
    assertThat(first.get("titulo").asString()).hasSize(100);
    assertThat(first.get("descricao").asString()).hasSize(200);
    assertThat(confirmed(detail.get("id").asString()).get("criadas").asInt()).isEqualTo(1);
  }

  @Test
  void aFormulaAtTheCutBoundaryStillFitsTheTitleAndDescriptionLimits() throws Exception {
    JsonNode detail = upload(csv(HEADER, "03/10/2026;-1,00;=" + "Z".repeat(300)));

    assertThat(line(detail, 1).get("titulo").asString()).hasSize(100).startsWith("'=");
    assertThat(line(detail, 1).get("descricao").asString().length()).isLessThanOrEqualTo(201);
    assertThat(confirmed(detail.get("id").asString()).get("criadas").asInt()).isEqualTo(1);
  }

  @Test
  void aTitleThatEndsInASpaceAfterTheCutKeepsTheDatabaseRule() throws Exception {
    String description = "A".repeat(99) + " " + "B".repeat(20);

    JsonNode detail = upload(csv(HEADER, "03/10/2026;-1,00;" + description));

    assertThat(line(detail, 1).get("titulo").asString()).isEqualTo("A".repeat(99));
    assertThat(line(detail, 1).get("descricao").asString()).isEqualTo(description);
    assertThat(confirmed(detail.get("id").asString()).get("criadas").asInt()).isEqualTo(1);
  }

  @Test
  void anEmptyDescriptionBecomesSemDescricao() throws Exception {
    JsonNode detail = upload(csv(HEADER, "03/10/2026;-1,00;"));

    assertThat(line(detail, 1).get("titulo").asString()).isEqualTo("Sem descrição");
  }

  @Test
  void readsWindows1252Accents() throws Exception {
    byte[] file =
        (HEADER + "\n03/10/2026;-1,00;AÇÚCAR E CAFÉ\n").getBytes(Charset.forName("windows-1252"));

    assertThat(line(upload(file), 1).get("titulo").asString()).isEqualTo("AÇÚCAR E CAFÉ");
  }

  @Test
  void readsUtf8WithBomAndCrlf() throws Exception {
    byte[] file =
        ("﻿" + HEADER + "\r\n03/10/2026;-1,00;PÃO\r\n04/10/2026;-2,00;LEITE\r\n")
            .getBytes(StandardCharsets.UTF_8);

    JsonNode detail = upload(file);

    assertThat(detail.get("totalLinhas").asInt()).isEqualTo(2);
    assertThat(line(detail, 1).get("titulo").asString()).isEqualTo("PÃO");
  }

  @Test
  void aQuotedCellKeepsTheSeparatorAndTheLineBreak() throws Exception {
    JsonNode detail =
        upload(
            csv(
                HEADER,
                "03/10/2026;-1,00;\"LOJA; CENTRO\nFILIAL \"\"2\"\"\"",
                "04/10/2026;-2,00;B"));

    assertThat(detail.get("totalLinhas").asInt()).isEqualTo(2);
    assertThat(line(detail, 1).get("titulo").asString()).isEqualTo("LOJA; CENTRO FILIAL \"2\"");
  }

  @Test
  void positiveExpenseMappingTreatsThePositiveAmountAsExpense() throws Exception {
    Map<String, Object> mapping = brMapping("Cartão");
    mapping.put("sinal", "positivo_despesa");
    String id = api.createMappingOk(actor.session(), mapping);

    JsonNode detail =
        api.uploadCsvOk(
            actor.session(),
            actor.accountId(),
            id,
            csv(HEADER, "03/10/2026;35,90;COMPRA", "04/10/2026;-10,00;ESTORNO"));

    assertThat(line(detail, 1).get("tipo").asString()).isEqualTo("despesa");
    assertThat(line(detail, 1).get("valorCentavos").asLong()).isEqualTo(3_590);
    assertThat(line(detail, 2).get("tipo").asString()).isEqualTo("receita");
    assertThat(line(detail, 2).get("valorCentavos").asLong()).isEqualTo(1_000);
  }

  @Test
  void anIdColumnDeduplicatesByIdAndIgnoresARepeatedIdInTheFile() throws Exception {
    Map<String, Object> mapping = brMapping("Com id");
    mapping.put("colunaId", 3);
    String id = api.createMappingOk(actor.session(), mapping);
    byte[] file =
        csv(
            "Data;Valor;Descricao;Id",
            "03/10/2026;-1,00;A;T1",
            "04/10/2026;-2,00;B;T2",
            "05/10/2026;-3,00;C;T1");

    JsonNode detail = api.uploadCsvOk(actor.session(), actor.accountId(), id, file);

    assertThat(line(detail, 1).get("proposta").asString()).isEqualTo("importar");
    assertThat(line(detail, 3).get("proposta").asString()).isEqualTo("ignorar");
    assertThat(line(detail, 3).get("motivo").asString()).isEqualTo("duplicada_no_arquivo");
    confirmed(detail.get("id").asString());

    // O banco reexporta a mesma transação com outra descrição: o id manda.
    JsonNode second =
        api.uploadCsvOk(
            actor.session(),
            actor.accountId(),
            id,
            csv(
                "Data;Valor;Descricao;Id",
                "03/10/2026;-1,00;A RENOMEADA;T1",
                "06/10/2026;-9,00;D;T9"));
    assertThat(line(second, 1).get("motivo").asString()).isEqualTo("ja_importada");
    assertThat(line(second, 2).get("proposta").asString()).isEqualTo("importar");
  }

  @Test
  void theSameIdOnAnotherAccountIsAnotherTransaction() throws Exception {
    Map<String, Object> mapping = brMapping("Com id");
    mapping.put("colunaId", 3);
    String id = api.createMappingOk(actor.session(), mapping);
    byte[] file = csv("Data;Valor;Descricao;Id", "03/10/2026;-1,00;A;T1");
    confirmed(api.uploadCsvOk(actor.session(), actor.accountId(), id, file).get("id").asString());
    String other = api.newAccount(actor.session(), "corrente");

    JsonNode detail =
        api.uploadCsvOk(
            actor.session(),
            other,
            id,
            csv("Data;Valor;Descricao;Id", "03/10/2026;-1,00;A;T1", ""));

    assertThat(line(detail, 1).get("proposta").asString()).isEqualTo("importar");
  }

  @Test
  void aCsvLineStillReconcilesWithAManualEntry() throws Exception {
    String entry =
        api.createEntry(
            actor.session(),
            "despesa",
            3590,
            "2026-10-01",
            actor.accountId(),
            actor.expenseCategoryId());

    JsonNode detail = upload(csv(HEADER, "04/10/2026;-35,90;PADARIA"));

    assertThat(line(detail, 1).get("proposta").asString()).isEqualTo("conciliar");
    assertThat(line(detail, 1).get("lancamentoId").asString()).isEqualTo(entry);
    assertThat(confirmed(detail.get("id").asString()).get("conciliadas").asInt()).isEqualTo(1);
    assertThat(entryCount()).isEqualTo(1);
  }

  @Test
  void theConfirmedEntryCarriesTheFileDateAsCompetence() throws Exception {
    confirmed(upload(csv(HEADER, "03/10/2026;-1.234,56;ALUGUEL")).get("id").asString());

    JsonNode created = entries().get(0);
    assertThat(created.get("titulo").asString()).isEqualTo("ALUGUEL");
    assertThat(created.get("valorCentavos").asLong()).isEqualTo(123_456);
    assertThat(created.get("dataCompetencia").asString()).isEqualTo("2026-10-03");
  }

  // Limites.

  @Test
  void answers413ForMoreThanOneMebibyte() throws Exception {
    MockHttpServletResponse response = tryUpload(new byte[(1 << 20) + 1]);

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(body(response).get("erro").asString()).isEqualTo("corpo grande demais");
  }

  @Test
  void answers422ForMoreThan2000Rows() throws Exception {
    StringBuilder text = new StringBuilder(HEADER).append('\n');
    for (int i = 0; i < 2001; i++) {
      text.append("03/10/2026;-1,00;L").append(i).append('\n');
    }

    MockHttpServletResponse response = tryUpload(text.toString().getBytes(StandardCharsets.UTF_8));

    assertThat(response.getStatus()).isEqualTo(422);
    assertThat(body(response).get("erro").asString()).isEqualTo("arquivo com mais de 2000 linhas");
    assertThat(body(api.list(actor.session()))).isEmpty();
  }

  @Test
  void accepts2000Rows() throws Exception {
    StringBuilder text = new StringBuilder(HEADER).append('\n');
    for (int i = 0; i < 2000; i++) {
      text.append("03/10/2026;-1,00;L").append(i).append('\n');
    }

    JsonNode detail = upload(text.toString().getBytes(StandardCharsets.UTF_8));

    assertThat(detail.get("totalLinhas").asInt()).isEqualTo(2000);
  }

  @Test
  void answers422ForMoreThan50ColumnsNulAndOtherUnreadableFiles() throws Exception {
    String wide = "03/10/2026;-1,00;A" + ";x".repeat(48);
    MockHttpServletResponse columns = tryUpload(csv(HEADER, wide));
    MockHttpServletResponse nul = tryUpload(csv(HEADER, "03/10/2026;-1,00;A\0B"));
    MockHttpServletResponse empty = tryUpload(new byte[0]);
    MockHttpServletResponse headerOnly = tryUpload(csv(HEADER));
    MockHttpServletResponse quote = tryUpload(csv(HEADER, "03/10/2026;-1,00;\"aberta"));

    assertThat(columns.getStatus()).isEqualTo(422);
    assertThat(body(columns).get("erro").asString())
        .isEqualTo("arquivo com mais de 50 colunas por linha");
    assertThat(nul.getStatus()).isEqualTo(422);
    assertThat(empty.getStatus()).isEqualTo(422);
    assertThat(headerOnly.getStatus()).isEqualTo(422);
    assertThat(quote.getStatus()).isEqualTo(422);
    assertThat(body(api.list(actor.session()))).isEmpty();
  }

  @Test
  void aParseErrorNeverEchoesTheContent() throws Exception {
    MockHttpServletResponse response = tryUpload(csv(HEADER, "03/10/2026;-1,00;\"SEGREDO-ABC"));

    assertThat(response.getStatus()).isEqualTo(422);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SEGREDO");
  }

  @Test
  void stopsAtTwentyPendingImportsBeforeReadingTheFile() throws Exception {
    for (int i = 0; i < 20; i++) {
      upload(csv(HEADER, "03/10/2026;-1,00;L" + i));
    }

    MockHttpServletResponse response = tryUpload("lixo\0".getBytes(StandardCharsets.UTF_8));

    // Com NUL o parse daria 422: o 409 prova que o teto veio antes dele.
    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(body(response).get("erro").asString())
        .isEqualTo("limite de importações pendentes atingido");
  }

  // Parâmetros e acesso.

  @Test
  void validatesTheParameters() throws Exception {
    byte[] file = csv(HEADER, "03/10/2026;-1,00;A");
    String random = UUID.randomUUID().toString();

    assertThat(api.uploadCsv(actor.session(), null, mappingId, file).getStatus()).isEqualTo(400);
    assertThat(api.uploadCsv(actor.session(), "x", mappingId, file).getStatus()).isEqualTo(400);
    assertThat(api.uploadCsv(actor.session(), actor.accountId(), null, file).getStatus())
        .isEqualTo(400);
    assertThat(api.uploadCsv(actor.session(), actor.accountId(), "x", file).getStatus())
        .isEqualTo(400);
    assertThat(api.uploadCsv(actor.session(), random, mappingId, file).getStatus()).isEqualTo(404);
    assertThat(api.uploadCsv(actor.session(), actor.accountId(), random, file).getStatus())
        .isEqualTo(404);
    MockHttpServletResponse extra =
        mvc.perform(
                post("/importacoes/csv?conta_id="
                        + actor.accountId()
                        + "&mapeamento_id="
                        + mappingId
                        + "&separador=virgula")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(file)
                    .cookie(actor.session()))
            .andReturn()
            .getResponse();
    assertThat(extra.getStatus()).isEqualTo(400);
  }

  @Test
  void refusesCryptoAndArchivedAccounts() throws Exception {
    byte[] file = csv(HEADER, "03/10/2026;-1,00;A");
    String crypto = api.newAccount(actor.session(), "cripto");
    String archived = api.newAccount(actor.session(), "corrente");
    api.setAccountArchived(actor.session(), archived);

    assertThat(api.uploadCsv(actor.session(), crypto, mappingId, file).getStatus()).isEqualTo(400);
    assertThat(api.uploadCsv(actor.session(), archived, mappingId, file).getStatus())
        .isEqualTo(400);
  }

  @Test
  void refusesAJsonBody() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/importacoes/csv?conta_id="
                        + actor.accountId()
                        + "&mapeamento_id="
                        + mappingId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .cookie(actor.session()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(415);
  }

  @Test
  void requiresALogin() throws Exception {
    assertThat(
            api.uploadCsv(null, actor.accountId(), mappingId, csv(HEADER, "03/10/2026;-1,00;A"))
                .getStatus())
        .isEqualTo(401);
  }
}
