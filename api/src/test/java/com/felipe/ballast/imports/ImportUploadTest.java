package com.felipe.ballast.imports;

import static com.felipe.ballast.imports.ImportsApi.body;
import static com.felipe.ballast.imports.ImportsApi.line;
import static com.felipe.ballast.imports.ImportsApi.ofx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.imports.ImportsApi.Tx;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ImportUploadTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private ImportsApi api;
  private ImportsApi.Actor actor;

  @BeforeEach
  void setUp() throws Exception {
    api = new ImportsApi(mvc);
    actor = api.newActor();
  }

  private long entryCount() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :u")
        .param("u", actor.user().id())
        .query(Long.class)
        .single();
  }

  @Test
  void createsAPendingImportWithOneLinePerTransaction() throws Exception {
    byte[] file =
        ofx(
            new Tx("20261003", "-35.90", "F1", "PADARIA"),
            new Tx("20261005", "1500,00", "F2", "SALARIO"));

    MockHttpServletResponse response = api.upload(actor.session(), actor.accountId(), file);

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode detail = body(response);
    assertThat(detail.get("status").asString()).isEqualTo("pendente");
    assertThat(detail.get("contaId").asString()).isEqualTo(actor.accountId());
    assertThat(detail.get("totalLinhas").asInt()).isEqualTo(2);
    JsonNode expense = line(detail, 1);
    assertThat(expense.get("data").asString()).isEqualTo("2026-10-03");
    assertThat(expense.get("tipo").asString()).isEqualTo("despesa");
    assertThat(expense.get("valorCentavos").asLong()).isEqualTo(3590);
    assertThat(expense.get("titulo").asString()).isEqualTo("PADARIA");
    assertThat(expense.get("proposta").asString()).isEqualTo("importar");
    assertThat(expense.get("acao").asString()).isEqualTo("importar");
    JsonNode income = line(detail, 2);
    assertThat(income.get("tipo").asString()).isEqualTo("receita");
    assertThat(income.get("valorCentavos").asLong()).isEqualTo(150_000);
  }

  @Test
  void keepsNothingInTheStatementBeforeConfirmation() throws Exception {
    api.uploadOk(
        actor.session(), actor.accountId(), ofx(new Tx("20261003", "-35.90", "F1", "PADARIA")));

    assertThat(entryCount()).isZero();
    JsonNode entries = body(api.listEntries(actor.session(), "2026-10-01", "2026-10-31"));
    assertThat(entries).isEmpty();
  }

  @Test
  void refusesTheSameFileTwiceWithTheExistingImportId() throws Exception {
    byte[] file = ofx(new Tx("20261003", "-35.90", "F1", "PADARIA"));
    String first = api.uploadOk(actor.session(), actor.accountId(), file).get("id").asString();

    MockHttpServletResponse second = api.upload(actor.session(), actor.accountId(), file);

    assertThat(second.getStatus()).isEqualTo(409);
    assertThat(body(second).get("importacaoId").asString()).isEqualTo(first);
  }

  @Test
  void discardingThePendingImportFreesTheFileHash() throws Exception {
    byte[] file = ofx(new Tx("20261003", "-35.90", "F1", "PADARIA"));
    String first = api.uploadOk(actor.session(), actor.accountId(), file).get("id").asString();
    assertThat(api.discard(actor.session(), first).getStatus()).isEqualTo(204);

    assertThat(api.upload(actor.session(), actor.accountId(), file).getStatus()).isEqualTo(201);
  }

  @Test
  void proposesReconcilingWithAnEntryOnTheSameAccountAndValueWithinThreeDays() throws Exception {
    String entry =
        api.createEntry(
            actor.session(),
            "despesa",
            3590,
            "2026-10-01",
            actor.accountId(),
            actor.expenseCategoryId());

    JsonNode detail =
        api.uploadOk(
            actor.session(), actor.accountId(), ofx(new Tx("20261004", "-35.90", "F1", "PADARIA")));

    JsonNode first = line(detail, 1);
    assertThat(first.get("proposta").asString()).isEqualTo("conciliar");
    assertThat(first.get("lancamentoId").asString()).isEqualTo(entry);
  }

  @Test
  void doesNotProposeReconcilingOutsideTheRules() throws Exception {
    String other = api.newAccount(actor.session(), "corrente");
    // Outra conta, outro tipo, outro valor e quatro dias de distância: nenhum deve casar.
    api.createEntry(
        actor.session(), "despesa", 3590, "2026-10-03", other, actor.expenseCategoryId());
    api.createEntry(
        actor.session(),
        "receita",
        3590,
        "2026-10-03",
        actor.accountId(),
        actor.incomeCategoryId());
    api.createEntry(
        actor.session(),
        "despesa",
        3591,
        "2026-10-03",
        actor.accountId(),
        actor.expenseCategoryId());
    api.createEntry(
        actor.session(),
        "despesa",
        3590,
        "2026-09-29",
        actor.accountId(),
        actor.expenseCategoryId());

    JsonNode detail =
        api.uploadOk(
            actor.session(), actor.accountId(), ofx(new Tx("20261003", "-35.90", "F1", "PADARIA")));

    assertThat(line(detail, 1).get("proposta").asString()).isEqualTo("importar");
  }

  @Test
  void reconcilesOneToOneWithTheClosestDate() throws Exception {
    String near =
        api.createEntry(
            actor.session(),
            "despesa",
            1000,
            "2026-10-10",
            actor.accountId(),
            actor.expenseCategoryId());
    api.createEntry(
        actor.session(),
        "despesa",
        1000,
        "2026-10-08",
        actor.accountId(),
        actor.expenseCategoryId());

    JsonNode detail =
        api.uploadOk(
            actor.session(),
            actor.accountId(),
            ofx(new Tx("20261010", "-10.00", "F1", "A"), new Tx("20261010", "-10.00", "F2", "B")));

    JsonNode first = line(detail, 1);
    JsonNode second = line(detail, 2);
    assertThat(first.get("lancamentoId").asString()).isEqualTo(near);
    assertThat(first.get("proposta").asString()).isEqualTo("conciliar");
    // A segunda linha fica com o de dois dias de distância; nenhum lançamento serve a duas linhas.
    assertThat(second.get("proposta").asString()).isEqualTo("conciliar");
    assertThat(second.get("lancamentoId").asString()).isNotEqualTo(near);
  }

  @Test
  void aLineWithoutAFreeEntryIsImportedNotReconciledTwice() throws Exception {
    api.createEntry(
        actor.session(),
        "despesa",
        1000,
        "2026-10-10",
        actor.accountId(),
        actor.expenseCategoryId());

    JsonNode detail =
        api.uploadOk(
            actor.session(),
            actor.accountId(),
            ofx(new Tx("20261010", "-10.00", "F1", "A"), new Tx("20261010", "-10.00", "F2", "B")));

    assertThat(line(detail, 1).get("proposta").asString()).isEqualTo("conciliar");
    assertThat(line(detail, 2).get("proposta").asString()).isEqualTo("importar");
  }

  @Test
  void marksUnreadableLinesAsInvalidAndIgnored() throws Exception {
    JsonNode detail =
        api.uploadOk(
            actor.session(),
            actor.accountId(),
            ofx(new Tx("20261003", "-10.005", "F1", "A"), new Tx("20261003", "-10.00", "F2", "B")));

    JsonNode invalid = line(detail, 1);
    assertThat(invalid.get("valida").asBoolean()).isFalse();
    assertThat(invalid.get("proposta").asString()).isEqualTo("ignorar");
    assertThat(invalid.get("motivo").asString()).isEqualTo("invalida");
    assertThat(invalid.get("valorCentavos").isNull()).isTrue();
    assertThat(detail.get("totalInvalidas").asInt()).isEqualTo(1);
    assertThat(line(detail, 2).get("valida").asBoolean()).isTrue();
  }

  @Test
  void readsAFileWithoutFitId() throws Exception {
    byte[] file =
        ofx(new Tx("20261003", "-10.00", null, "A"), new Tx("20261003", "-10.00", null, "A"));

    JsonNode detail = api.uploadOk(actor.session(), actor.accountId(), file);

    assertThat(line(detail, 1).get("proposta").asString()).isEqualTo("importar");
    assertThat(line(detail, 2).get("proposta").asString()).isEqualTo("importar");
  }

  @Test
  void ignoresTheRepeatedFitIdInsideTheSameFile() throws Exception {
    JsonNode detail =
        api.uploadOk(
            actor.session(),
            actor.accountId(),
            ofx(new Tx("20261003", "-10.00", "F1", "A"), new Tx("20261004", "-12.00", "F1", "B")));

    JsonNode second = line(detail, 2);
    assertThat(second.get("proposta").asString()).isEqualTo("ignorar");
    assertThat(second.get("motivo").asString()).isEqualTo("duplicada_no_arquivo");
  }

  @Test
  void answers422ForGarbageDoctypeEmptyAndTooManyTransactions() throws Exception {
    byte[] garbage = new byte[2048];
    new Random(3).nextBytes(garbage);
    byte[] doctype =
        "<?xml version=\"1.0\"?><!DOCTYPE OFX [<!ENTITY x \"y\">]><OFX><STMTTRN></STMTTRN></OFX>"
            .getBytes(StandardCharsets.UTF_8);
    Tx[] many = new Tx[2001];
    for (int i = 0; i < many.length; i++) {
      many[i] = new Tx("20261003", "-1.00", "F" + i, "X");
    }

    assertThat(api.upload(actor.session(), actor.accountId(), garbage).getStatus()).isEqualTo(422);
    assertThat(api.upload(actor.session(), actor.accountId(), doctype).getStatus()).isEqualTo(422);
    assertThat(api.upload(actor.session(), actor.accountId(), new byte[0]).getStatus())
        .isEqualTo(422);
    assertThat(api.upload(actor.session(), actor.accountId(), ofx(many)).getStatus())
        .isEqualTo(422);
    assertThat(entryCount()).isZero();
  }

  @Test
  void answers413ForAFileOverOneMebibyte() throws Exception {
    byte[] big = new byte[(1 << 20) + 1];

    MockHttpServletResponse response = api.upload(actor.session(), actor.accountId(), big);

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(body(response).get("erro").asString()).isEqualTo("corpo grande demais");
  }

  @Test
  void validatesTheAccountParameter() throws Exception {
    byte[] file = ofx(new Tx("20261003", "-10.00", "F1", "A"));

    assertThat(api.upload(actor.session(), null, file).getStatus()).isEqualTo(400);
    assertThat(api.upload(actor.session(), "nao-e-uuid", file).getStatus()).isEqualTo(400);
    assertThat(
            api.upload(actor.session(), java.util.UUID.randomUUID().toString(), file).getStatus())
        .isEqualTo(404);
  }

  @Test
  void refusesCryptoAndArchivedAccounts() throws Exception {
    byte[] file = ofx(new Tx("20261003", "-10.00", "F1", "A"));
    String crypto = api.newAccount(actor.session(), "cripto");
    String archived = api.newAccount(actor.session(), "corrente");
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/contas/" + archived)
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"arquivada\":true}"))
        .andReturn();

    assertThat(api.upload(actor.session(), crypto, file).getStatus()).isEqualTo(400);
    assertThat(api.upload(actor.session(), archived, file).getStatus()).isEqualTo(400);
  }

  @Test
  void refusesAJsonBody() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/importacoes?conta_id=" + actor.accountId())
                    .cookie(actor.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(415);
  }

  @Test
  void keepsTheFourKibibyteLimitOnOtherRoutes() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/lancamentos")
                    .cookie(actor.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(" ".repeat(5000)))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(413);
  }

  @Test
  void stopsAtTwentyPendingImports() throws Exception {
    for (int i = 0; i < 20; i++) {
      api.uploadOk(
          actor.session(), actor.accountId(), ofx(new Tx("20261003", "-1.00", "F" + i, "X" + i)));
    }

    MockHttpServletResponse response =
        api.upload(
            actor.session(), actor.accountId(), ofx(new Tx("20261003", "-1.00", "F99", "X99")));

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(body(response).get("erro").asString())
        .isEqualTo("limite de importações pendentes atingido");
  }

  @Test
  void deletingTheAccountDeletesItsImports() throws Exception {
    String account = api.newAccount(actor.session(), "corrente");
    String importId =
        api.uploadOk(actor.session(), account, ofx(new Tx("20261003", "-1.00", "F1", "X")))
            .get("id")
            .asString();

    MockHttpServletResponse deleted =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/contas/" + account)
                    .cookie(actor.session()))
            .andReturn()
            .getResponse();

    assertThat(deleted.getStatus()).isEqualTo(204);
    assertThat(api.find(actor.session(), importId).getStatus()).isEqualTo(404);
  }

  @Test
  void requiresALogin() throws Exception {
    assertThat(
            api.upload(null, actor.accountId(), ofx(new Tx("20261003", "-1.00", "F", "X")))
                .getStatus())
        .isEqualTo(401);
  }
}
