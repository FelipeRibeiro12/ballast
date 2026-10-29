package com.felipe.ballast.imports;

import static com.felipe.ballast.imports.ImportsApi.body;
import static com.felipe.ballast.imports.ImportsApi.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
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
class PdfUploadTest {

  // Cartão fecha dia 10 e vence dia 20 (ADR-031).
  private static final List<String> STATEMENT =
      List.of(
          "FATURA DO CARTAO",
          "Vencimento 20/01/2027 (fora da referência: só o ano explícito vale aqui)",
          "28/12/2026 SUPERMERCADO CENTRAL 1.234,56",
          "05/01/2027 NOTEBOOK PARC 03/10 300,00",
          "06/01/2027 ESTORNO LOJA X 50,00 CR",
          "07/01/2027 PAGAMENTO RECEBIDO 800,00 CR",
          "08/01/2027 =HYPERLINK(1) 9,90",
          "09/01/2027 LINHA SEM VALOR",
          "Total da fatura 1.500,00",
          "31/02 DATA IMPOSSIVEL 10,00");

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private ImportsApi api;
  private ImportsApi.Actor actor;
  private String cardId;

  @BeforeEach
  void setUp() throws Exception {
    api = new ImportsApi(mvc);
    actor = api.newActor();
    cardId = api.newCard(actor.session(), actor.accountId(), 10, 20);
  }

  private long entryCount() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :u")
        .param("u", actor.user().id())
        .query(Long.class)
        .single();
  }

  private MockHttpServletResponse tryUpload(byte[] pdf) throws Exception {
    return api.uploadPdf(actor.session(), cardId, pdf);
  }

  @Test
  void readsKnownLinesAndKeepsTheRest() throws Exception {
    JsonNode detail = api.uploadPdfOk(actor.session(), cardId, PdfFixtures.statement(STATEMENT));

    assertThat(detail.get("status").asString()).isEqualTo("pendente");
    JsonNode market = line(detail, 1);
    assertThat(market.get("data").asString()).isEqualTo("2026-12-28");
    assertThat(market.get("tipo").asString()).isEqualTo("despesa");
    assertThat(market.get("valorCentavos").asLong()).isEqualTo(123_456);
    assertThat(market.get("proposta").asString()).isEqualTo("importar");
    JsonNode notebook = line(detail, 2);
    assertThat(notebook.get("data").asString()).isEqualTo("2027-01-05");
    assertThat(notebook.get("valorCentavos").asLong()).isEqualTo(30_000);
    assertThat(notebook.get("titulo").asString()).contains("PARC 03/10");
    JsonNode refund = line(detail, 3);
    assertThat(refund.get("tipo").asString()).isEqualTo("receita");
    assertThat(refund.get("valorCentavos").asLong()).isEqualTo(5_000);
    JsonNode payment = line(detail, 4);
    assertThat(payment.get("valida").asBoolean()).isFalse();
    assertThat(payment.get("proposta").asString()).isEqualTo("ignorar");
    assertThat(line(detail, 5).get("titulo").asString()).startsWith("'=HYPERLINK");
    JsonNode noValue = detail.get("linhas");
    boolean sawRawLine = false;
    for (JsonNode l : noValue) {
      if (l.get("titulo").asString().contains("Total da fatura")) {
        sawRawLine = !l.get("valida").asBoolean() && "ignorar".equals(l.get("acao").asString());
      }
    }
    assertThat(sawRawLine).isTrue();
    assertThat(entryCount()).isZero();
  }

  @Test
  void confirmationCreatesEntriesWithCardDates() throws Exception {
    JsonNode detail =
        api.uploadPdfOk(
            actor.session(),
            cardId,
            PdfFixtures.statement(List.of("05/10/2026 CAFE 10,00", "28/10/2026 JANTAR 20,00")));
    assertThat(entryCount()).isZero();

    MockHttpServletResponse response =
        api.confirm(actor.session(), detail.get("id").asString(), null);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(body(response).get("criadas").asInt()).isEqualTo(2);
    List<Map<String, Object>> rows =
        jdbc.sql(
                "SELECT titulo, data_competencia::text AS comp, data_caixa::text AS caixa"
                    + " FROM lancamento WHERE usuario_id = :u ORDER BY titulo")
            .param("u", actor.user().id())
            .query()
            .listOfRows();
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0))
        .containsEntry("comp", "2026-10-05")
        .containsEntry("caixa", "2026-10-20");
    // 20/11 é feriado (Consciência Negra): o vencimento vai para o próximo dia útil (ADR-060).
    assertThat(rows.get(1))
        .containsEntry("comp", "2026-10-28")
        .containsEntry("caixa", "2026-11-23");
  }

  @Test
  void sameFileTwiceIsRefusedAndSameLinesInAnotherFileAreNotDuplicated() throws Exception {
    List<String> lines = List.of("05/10/2026 CAFE 10,00", "05/10/2026 CAFE 10,00");
    JsonNode first = api.uploadPdfOk(actor.session(), cardId, PdfFixtures.statement(lines));
    assertThat(tryUpload(PdfFixtures.statement(lines)).getStatus()).isIn(201, 409);
    api.confirm(actor.session(), first.get("id").asString(), null);
    assertThat(entryCount()).isEqualTo(2);

    // Bytes diferentes (linha extra), mesmas duas compras: as duas caem em já importada.
    JsonNode second =
        api.uploadPdfOk(
            actor.session(),
            cardId,
            PdfFixtures.statement(
                List.of(
                    "Vencimento 20/11/2026",
                    "05/10/2026 CAFE 10,00",
                    "05/10/2026 CAFE 10,00",
                    "06/10/2026 PAO 5,00")));
    assertThat(line(second, 1).get("motivo").asString()).isEqualTo("ja_importada");
    assertThat(line(second, 2).get("motivo").asString()).isEqualTo("ja_importada");
    assertThat(line(second, 3).get("proposta").asString()).isEqualTo("importar");
    api.confirm(actor.session(), second.get("id").asString(), null);
    assertThat(entryCount()).isEqualTo(3);
  }

  @Test
  void exactSameBytesAreAConflict() throws Exception {
    byte[] pdf = PdfFixtures.statement(List.of("05/10/2026 CAFE 10,00"));
    JsonNode first = api.uploadPdfOk(actor.session(), cardId, pdf);
    MockHttpServletResponse again = tryUpload(pdf);
    assertThat(again.getStatus()).isEqualTo(409);
    assertThat(body(again).get("importacaoId").asString()).isEqualTo(first.get("id").asString());
  }

  @Test
  void unreadableFilesAreClear422WithoutEchoingTheFile() throws Exception {
    assertThat(tryUpload(new byte[0]).getStatus()).isEqualTo(422);
    MockHttpServletResponse notPdf = tryUpload("segredo-no-arquivo".getBytes());
    assertThat(notPdf.getStatus()).isEqualTo(422);
    assertThat(notPdf.getContentAsString()).doesNotContain("segredo");
    assertThat(tryUpload("%PDF-1.7\nlixo sem estrutura".getBytes()).getStatus()).isEqualTo(422);
    MockHttpServletResponse blank = tryUpload(PdfFixtures.blank());
    assertThat(blank.getStatus()).isEqualTo(422);
    assertThat(body(blank).get("erro").asString()).contains("sem texto".substring(0, 0) + "texto");
    assertThat(tryUpload(PdfFixtures.statement(List.of("só texto sem transação"))).getStatus())
        .isEqualTo(422);
    assertThat(tryUpload(PdfFixtures.withPages(31)).getStatus()).isEqualTo(422);
    assertThat(entryCount()).isZero();
  }

  @Test
  void encryptedPdfIsRefused() throws Exception {
    byte[] withUserPassword = PdfFixtures.encrypted(List.of("05/10/2026 CAFE 10,00"), "senha123");
    MockHttpServletResponse response = tryUpload(withUserPassword);
    assertThat(response.getStatus()).isEqualTo(422);
    assertThat(body(response).get("erro").asString()).contains("senha");
    byte[] ownerOnly = PdfFixtures.encrypted(List.of("05/10/2026 CAFE 10,00"), "");
    assertThat(tryUpload(ownerOnly).getStatus()).isEqualTo(422);
  }

  @Test
  void fileOverFiveMiBIs413() throws Exception {
    assertThat(tryUpload(new byte[(5 << 20) + 1]).getStatus()).isEqualTo(413);
  }

  @Test
  void onlyCardAccountsAndOnlyMyOwn() throws Exception {
    byte[] pdf = PdfFixtures.statement(List.of("05/10/2026 CAFE 10,00"));
    assertThat(api.uploadPdf(actor.session(), actor.accountId(), pdf).getStatus()).isEqualTo(400);

    ImportsApi.Actor other = api.newActor();
    assertThat(api.uploadPdf(other.session(), cardId, pdf).getStatus()).isEqualTo(404);
    assertThat(api.uploadPdf(actor.session(), null, pdf).getStatus()).isEqualTo(400);

    JsonNode detail = api.uploadPdfOk(actor.session(), cardId, pdf);
    MockHttpServletResponse peek =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/importacoes/" + detail.get("id").asString())
                    .cookie(other.session()))
            .andReturn()
            .getResponse();
    assertThat(peek.getStatus()).isEqualTo(404);
    assertThat(api.confirm(other.session(), detail.get("id").asString(), null).getStatus())
        .isEqualTo(404);
  }
}
