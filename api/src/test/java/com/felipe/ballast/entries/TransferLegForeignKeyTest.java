package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.TransfersApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// A checagem da aplicação diz "não é perna", como numa corrida em que a ligação surge entre a
// leitura e o DELETE. As FKs da V7 recusam, e o serviço traduz no mesmo resultado.
@IntegrationTest
class TransferLegForeignKeyTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired EntryRepository repository;
  @Autowired WriteTransaction transaction;
  private EntryService service;
  private EntriesApi.Actor actor;
  private UUID outId;

  @BeforeEach
  void setUp() throws Exception {
    EntriesApi api = new EntriesApi(mvc);
    actor = api.newActor();
    String other = api.newAccount(actor.session(), "B " + UUID.randomUUID());
    JsonNode created =
        new TransfersApi(mvc).createOk(actor.session(), body(actor.accountId(), other, 500));
    outId = UUID.fromString(created.get("saidaId").asString());
    EntryQueries blind =
        new EntryQueries(jdbc) {
          @Override
          boolean isTransferLeg(UUID userId, UUID entryId) {
            return false;
          }
        };
    service = new EntryService(repository, blind, transaction);
  }

  private long legs() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :id")
        .param("id", actor.user().id())
        .query(Long.class)
        .single();
  }

  @Test
  void deleteOfALegIsRejectedByTheDatabase() {
    assertThat(service.delete(actor.user().id(), outId))
        .isEqualTo(EntryService.DeleteResult.TRANSFER_LEG);
    assertThat(legs()).isEqualTo(2);
  }

  @Test
  void updateOfALegIsRejectedByTheDatabase() {
    EntryInput input =
        new EntryInput(
            EntryType.EXPENSE,
            "invadido",
            null,
            new BigDecimal("1.00"),
            UUID.fromString(actor.expenseCategoryId()),
            UUID.fromString(actor.accountId()),
            LocalDate.of(2026, 3, 10));

    assertThat(service.update(actor.user().id(), outId, input))
        .isEqualTo(EntryService.SaveResult.Rejected.TRANSFER_LEG);
    assertThat(legs()).isEqualTo(2);
  }
}
