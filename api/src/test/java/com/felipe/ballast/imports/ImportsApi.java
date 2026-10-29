package com.felipe.ballast.imports;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Chamadas às rotas de importação como o cliente faria, mais um gerador de OFX para os testes.
final class ImportsApi {

  static final JsonMapper JSON = JsonMapper.builder().build();

  record Actor(
      TestUsers.Registered user,
      Cookie session,
      String accountId,
      String incomeCategoryId,
      String expenseCategoryId) {}

  // Uma transação do extrato. amount com sinal, como no arquivo.
  record Tx(String date, String amount, String fitId, String memo) {}

  private final MockMvc mvc;

  ImportsApi(MockMvc mvc) {
    this.mvc = mvc;
  }

  Actor newActor() throws Exception {
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered user = users.register();
    Cookie session = users.session(user);
    return new Actor(
        user,
        session,
        newAccount(session, "corrente"),
        categoryId(session, "Salário"),
        categoryId(session, "Mercado"));
  }

  String newAccount(Cookie session, String type) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/contas")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"nome\":\"Conta " + UUID.randomUUID() + "\",\"tipo\":\"" + type + "\"}"))
            .andReturn()
            .getResponse();
    return body(response).get("id").asString();
  }

  String newCard(Cookie session, String paymentAccountId, int closingDay, int dueDay)
      throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/contas")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"nome\":\"Cartão "
                            + UUID.randomUUID()
                            + "\",\"tipo\":\"cartao_credito\",\"cartao\":{\"limiteCentavos\":1000000,"
                            + "\"diaFechamento\":"
                            + closingDay
                            + ",\"diaVencimento\":"
                            + dueDay
                            + ",\"contaPagamentoId\":\""
                            + paymentAccountId
                            + "\"}}"))
            .andReturn()
            .getResponse();
    return body(response).get("id").asString();
  }

  void setAccountArchived(Cookie session, String accountId) throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/contas/" + accountId)
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"arquivada\":true}"))
        .andReturn();
  }

  String categoryId(Cookie session, String name) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(get("/categorias").cookie(session)).andReturn().getResponse();
    for (JsonNode item : body(response)) {
      if (item.get("nome").asString().equals(name)) {
        return item.get("id").asString();
      }
    }
    throw new IllegalStateException("categoria de teste não encontrada: " + name);
  }

  // Lançamento manual, para a conciliação ter o que casar.
  String createEntry(
      Cookie session, String type, long cents, String date, String accountId, String categoryId)
      throws Exception {
    Map<String, Object> entry =
        Map.of(
            "tipo", type,
            "titulo", "Manual",
            "valorCentavos", cents,
            "categoriaId", categoryId,
            "contaId", accountId,
            "data", date);
    MockHttpServletResponse response =
        mvc.perform(
                post("/lancamentos")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(entry)))
            .andReturn()
            .getResponse();
    if (response.getStatus() != 201) {
      throw new IllegalStateException("lançamento de teste falhou: " + response.getStatus());
    }
    return body(response).get("id").asString();
  }

  // OFX 1.x (SGML) em Windows-1252, como os bancos brasileiros mandam.
  static byte[] ofx(Tx... transactions) {
    StringBuilder text =
        new StringBuilder(
            "OFXHEADER:100\r\nDATA:OFXSGML\r\nVERSION:102\r\nCHARSET:1252\r\n\r\n"
                + "<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>\r\n");
    for (Tx tx : transactions) {
      text.append("<STMTTRN>\r\n<TRNTYPE>OTHER\r\n<DTPOSTED>").append(tx.date()).append("\r\n");
      text.append("<TRNAMT>").append(tx.amount()).append("\r\n");
      if (tx.fitId() != null) {
        text.append("<FITID>").append(tx.fitId()).append("\r\n");
      }
      text.append("<MEMO>").append(tx.memo()).append("\r\n</STMTTRN>\r\n");
    }
    text.append("</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>");
    return text.toString().getBytes(Charset.forName("windows-1252"));
  }

  MockHttpServletResponse upload(Cookie session, String accountId, byte[] content)
      throws Exception {
    String query = accountId == null ? "" : "?conta_id=" + accountId;
    MockHttpServletRequestBuilder request =
        post("/importacoes" + query)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(content);
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  // Envia e devolve o corpo; falha o teste se não for 201.
  JsonNode uploadOk(Cookie session, String accountId, byte[] content) throws Exception {
    MockHttpServletResponse response = upload(session, accountId, content);
    if (response.getStatus() != 201) {
      throw new IllegalStateException(
          "envio de teste falhou: "
              + response.getStatus()
              + " "
              + response.getContentAsString(StandardCharsets.UTF_8));
    }
    return body(response);
  }

  MockHttpServletResponse list(Cookie session) throws Exception {
    return send(get("/importacoes"), session);
  }

  MockHttpServletResponse find(Cookie session, String id) throws Exception {
    return send(get("/importacoes/" + id), session);
  }

  MockHttpServletResponse discard(Cookie session, String id) throws Exception {
    return send(delete("/importacoes/" + id), session);
  }

  MockHttpServletResponse decide(Cookie session, String id, String lineId, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        put("/importacoes/" + id + "/linhas/" + lineId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(body));
    return send(request, session);
  }

  MockHttpServletResponse confirm(Cookie session, String id, String idempotencyKey)
      throws Exception {
    MockHttpServletRequestBuilder request = post("/importacoes/" + id + "/confirmacao");
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return send(request, session);
  }

  MockHttpServletResponse listEntries(Cookie session, String from, String to) throws Exception {
    return send(get("/lancamentos?de=" + from + "&ate=" + to), session);
  }

  private MockHttpServletResponse send(MockHttpServletRequestBuilder request, Cookie session)
      throws Exception {
    if (session != null) {
      request.cookie(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  // A linha da ordem pedida (a partir de 1) no detalhe de uma importação.
  static JsonNode line(JsonNode detail, int order) {
    for (JsonNode line : detail.get("linhas")) {
      if (line.get("ordem").asInt() == order) {
        return line;
      }
    }
    throw new IllegalStateException("linha " + order + " não existe");
  }
}
