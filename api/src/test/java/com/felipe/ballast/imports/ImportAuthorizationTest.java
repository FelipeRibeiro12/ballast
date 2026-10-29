package com.felipe.ballast.imports;

import static com.felipe.ballast.imports.ImportsApi.body;
import static com.felipe.ballast.imports.ImportsApi.line;
import static com.felipe.ballast.imports.ImportsApi.ofx;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.imports.ImportsApi.Tx;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Usuário A é dono de tudo; B tenta chegar nos recursos de A por todas as rotas.
@IntegrationTest
class ImportAuthorizationTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private ImportsApi api;
  private ImportsApi.Actor owner;
  private ImportsApi.Actor intruder;
  private String importId;
  private String lineId;

  @BeforeEach
  void setUp() throws Exception {
    api = new ImportsApi(mvc);
    owner = api.newActor();
    intruder = api.newActor();
    JsonNode detail =
        api.uploadOk(
            owner.session(), owner.accountId(), ofx(new Tx("20261003", "-35.90", "F1", "PADARIA")));
    importId = detail.get("id").asString();
    lineId = line(detail, 1).get("id").asString();
  }

  private static Map<String, Object> decision(String action) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("acao", action);
    return body;
  }

  private long ownerEntryCount() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :u")
        .param("u", owner.user().id())
        .query(Long.class)
        .single();
  }

  @Test
  void anotherUsersImportIsNotFoundInEveryRoute() throws Exception {
    assertThat(api.find(intruder.session(), importId).getStatus()).isEqualTo(404);
    assertThat(api.discard(intruder.session(), importId).getStatus()).isEqualTo(404);
    assertThat(api.confirm(intruder.session(), importId, null).getStatus()).isEqualTo(404);
    assertThat(api.confirm(intruder.session(), importId, "chave-do-intruso").getStatus())
        .isEqualTo(404);
    assertThat(api.decide(intruder.session(), importId, lineId, decision("ignorar")).getStatus())
        .isEqualTo(404);

    // Nada mudou para o dono.
    JsonNode detail = body(api.find(owner.session(), importId));
    assertThat(detail.get("status").asString()).isEqualTo("pendente");
    assertThat(line(detail, 1).get("decisao").isNull()).isTrue();
    assertThat(ownerEntryCount()).isZero();
  }

  @Test
  void aLineOfAnotherUsersImportCannotBeReachedThroughOwnImport() throws Exception {
    String ownImport =
        api.uploadOk(
                intruder.session(),
                intruder.accountId(),
                ofx(new Tx("20261003", "-35.90", "F1", "PADARIA")))
            .get("id")
            .asString();

    assertThat(api.decide(intruder.session(), ownImport, lineId, decision("ignorar")).getStatus())
        .isEqualTo(404);
    assertThat(
            api.decide(
                    intruder.session(), importId, UUID.randomUUID().toString(), decision("ignorar"))
                .getStatus())
        .isEqualTo(404);
    assertThat(line(body(api.find(owner.session(), importId)), 1).get("decisao").isNull()).isTrue();
  }

  @Test
  void uploadingToAnotherUsersAccountIsNotFound() throws Exception {
    assertThat(
            api.upload(
                    intruder.session(),
                    owner.accountId(),
                    ofx(new Tx("20261004", "-1.00", "Z1", "OUTRO")))
                .getStatus())
        .isEqualTo(404);
    assertThat(body(api.list(owner.session()))).hasSize(1);
  }

  @Test
  void anotherUsersEntryOrCategoryCannotBeUsedInADecision() throws Exception {
    JsonNode own =
        api.uploadOk(
            intruder.session(),
            intruder.accountId(),
            ofx(new Tx("20261003", "-35.90", "F1", "PADARIA")));
    String ownImport = own.get("id").asString();
    String ownLine = line(own, 1).get("id").asString();
    String ownersEntry =
        api.createEntry(
            owner.session(),
            "despesa",
            3590,
            "2026-10-03",
            owner.accountId(),
            owner.expenseCategoryId());
    Map<String, Object> reconcile = decision("conciliar");
    reconcile.put("lancamentoId", ownersEntry);
    Map<String, Object> category = decision("importar");
    category.put("categoriaId", owner.expenseCategoryId());

    assertThat(api.decide(intruder.session(), ownImport, ownLine, reconcile).getStatus())
        .isEqualTo(404);
    assertThat(api.decide(intruder.session(), ownImport, ownLine, category).getStatus())
        .isEqualTo(404);
  }

  @Test
  void aProposalNeverReconcilesWithAnotherUsersEntry() throws Exception {
    api.createEntry(
        owner.session(),
        "despesa",
        5000,
        "2026-10-03",
        owner.accountId(),
        owner.expenseCategoryId());

    JsonNode detail =
        api.uploadOk(
            intruder.session(),
            intruder.accountId(),
            ofx(new Tx("20261003", "-50.00", "F1", "MESMO VALOR")));

    assertThat(line(detail, 1).get("proposta").asString()).isEqualTo("importar");
  }

  @Test
  void sameFitIdAndFileOfTwoUsersDoNotCollide() throws Exception {
    byte[] file = ofx(new Tx("20261003", "-35.90", "F1", "PADARIA"));

    JsonNode theirs = api.uploadOk(intruder.session(), intruder.accountId(), file);

    assertThat(api.confirm(intruder.session(), theirs.get("id").asString(), null).getStatus())
        .isEqualTo(200);
    assertThat(api.confirm(owner.session(), importId, null).getStatus()).isEqualTo(200);
    assertThat(ownerEntryCount()).isEqualTo(1);
  }

  @Test
  void anotherUsersMappingAndAccountAreNotFoundInTheCsvRoutes() throws Exception {
    String ownersMapping = api.createMappingOk(owner.session(), ImportsApi.brMapping("Do dono"));
    String intrudersMapping =
        api.createMappingOk(intruder.session(), ImportsApi.brMapping("Do intruso"));
    byte[] file = ImportsApi.csv("D;V;X", "03/10/2026;-1,00;A");

    // Mapeamento do dono com a conta do intruso, e conta do dono com o mapeamento do intruso.
    assertThat(
            api.uploadCsv(intruder.session(), intruder.accountId(), ownersMapping, file)
                .getStatus())
        .isEqualTo(404);
    assertThat(
            api.uploadCsv(intruder.session(), owner.accountId(), intrudersMapping, file)
                .getStatus())
        .isEqualTo(404);
    assertThat(
            api.uploadCsv(intruder.session(), owner.accountId(), ownersMapping, file).getStatus())
        .isEqualTo(404);
    assertThat(body(api.list(intruder.session()))).isEmpty();
  }

  @Test
  void anotherUsersMappingCannotBeListedChangedOrDeleted() throws Exception {
    String ownersMapping = api.createMappingOk(owner.session(), ImportsApi.brMapping("Do dono"));

    assertThat(body(api.listMappings(intruder.session()))).isEmpty();
    assertThat(
            api.updateMapping(intruder.session(), ownersMapping, ImportsApi.brMapping("Roubado"))
                .getStatus())
        .isEqualTo(404);
    assertThat(api.deleteMapping(intruder.session(), ownersMapping).getStatus()).isEqualTo(404);

    JsonNode kept = body(api.listMappings(owner.session()));
    assertThat(kept).hasSize(1);
    assertThat(kept.get(0).get("nome").asString()).isEqualTo("Do dono");
  }

  @Test
  void sameMappingNameOfTwoUsersAndTheSameCsvDoNotCollide() throws Exception {
    String ownersMapping = api.createMappingOk(owner.session(), ImportsApi.brMapping("Banco"));
    String intrudersMapping =
        api.createMappingOk(intruder.session(), ImportsApi.brMapping("Banco"));
    byte[] file = ImportsApi.csv("D;V;X", "03/10/2026;-1,00;A");

    JsonNode mine = api.uploadCsvOk(owner.session(), owner.accountId(), ownersMapping, file);
    JsonNode theirs =
        api.uploadCsvOk(intruder.session(), intruder.accountId(), intrudersMapping, file);

    assertThat(api.confirm(intruder.session(), theirs.get("id").asString(), null).getStatus())
        .isEqualTo(200);
    assertThat(api.confirm(owner.session(), mine.get("id").asString(), null).getStatus())
        .isEqualTo(200);
    assertThat(ownerEntryCount()).isEqualTo(1);
  }

  @Test
  void everyCsvRouteRequiresALogin() throws Exception {
    String anyId = UUID.randomUUID().toString();
    byte[] file = ImportsApi.csv("D;V;X", "03/10/2026;-1,00;A");

    assertThat(api.listMappings(null).getStatus()).isEqualTo(401);
    assertThat(api.createMapping(null, ImportsApi.brMapping("A")).getStatus()).isEqualTo(401);
    assertThat(api.updateMapping(null, anyId, ImportsApi.brMapping("A")).getStatus())
        .isEqualTo(401);
    assertThat(api.deleteMapping(null, anyId).getStatus()).isEqualTo(401);
    assertThat(api.uploadCsv(null, owner.accountId(), anyId, file).getStatus()).isEqualTo(401);
    assertThat(api.previewCsv(null, ImportsApi.BR_PREVIEW_QUERY, file).getStatus()).isEqualTo(401);
  }

  @Test
  void everyRouteRequiresALogin() throws Exception {
    assertThat(api.list(null).getStatus()).isEqualTo(401);
    assertThat(api.find(null, importId).getStatus()).isEqualTo(401);
    assertThat(api.discard(null, importId).getStatus()).isEqualTo(401);
    assertThat(api.decide(null, importId, lineId, decision("ignorar")).getStatus()).isEqualTo(401);
    assertThat(api.confirm(null, importId, null).getStatus()).isEqualTo(401);
    assertThat(
            api.upload(null, owner.accountId(), ofx(new Tx("20261003", "-1.00", "F", "X")))
                .getStatus())
        .isEqualTo(401);
  }
}
