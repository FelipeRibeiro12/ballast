package com.felipe.ballast.entries;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.accounts.BenefitApi;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

// A conferência da aplicação responde "permitida" para tudo, como numa troca de lista entre a
// leitura e o INSERT. Só o gatilho da V12 segura, e o resultado tem de ser o mesmo 400.
@IntegrationTest
class BenefitEntryTriggerTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired EntryRepository repository;
  @Autowired WriteTransaction transaction;
  private EntryService service;
  private EntriesApi.Actor actor;
  private String benefit;
  private String outside;
  private String inside;

  @BeforeEach
  void setUp() throws Exception {
    EntriesApi api = new EntriesApi(mvc);
    BenefitApi benefits = new BenefitApi(mvc);
    actor = api.newActor();
    inside = actor.expenseCategoryId();
    outside = api.categoryId(actor.session(), "Lazer");
    benefit = benefits.createOk(actor.session(), "VR", List.of(inside));
    EntryQueries alwaysPermitted =
        new EntryQueries(jdbc) {
          @Override
          boolean isCategoryPermitted(UUID userId, UUID accountId, UUID categoryId) {
            return true;
          }
        };
    service = new EntryService(repository, alwaysPermitted, transaction);
  }

  private EntryInput expense(String categoryId) {
    return new EntryInput(
        EntryType.EXPENSE,
        "teste",
        null,
        new BigDecimal("10.00"),
        UUID.fromString(categoryId),
        UUID.fromString(benefit),
        LocalDate.of(2026, 3, 10));
  }

  private long countEntries() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE conta_id = :id")
        .param("id", UUID.fromString(benefit))
        .query(Long.class)
        .single();
  }

  @Test
  void createOutsideTheListIsRejectedByTheTrigger() {
    EntryService.SaveResult result = service.create(actor.user().id(), expense(outside));

    assertThat(result).isEqualTo(EntryService.SaveResult.Rejected.CATEGORY_NOT_PERMITTED);
    assertThat(countEntries()).isZero();
  }

  @Test
  void updateOutsideTheListIsRejectedByTheTrigger() {
    EntryService.SaveResult created = service.create(actor.user().id(), expense(inside));
    UUID id = ((EntryService.SaveResult.Saved) created).entry().id();

    EntryService.SaveResult result = service.update(actor.user().id(), id, expense(outside));

    assertThat(result).isEqualTo(EntryService.SaveResult.Rejected.CATEGORY_NOT_PERMITTED);
    assertThat(
            jdbc.sql("SELECT categoria_id FROM lancamento WHERE id = :id")
                .param("id", id)
                .query(UUID.class)
                .single())
        .isEqualTo(UUID.fromString(inside));
  }

  @Test
  void insideTheListPasses() {
    assertThat(service.create(actor.user().id(), expense(inside)))
        .isInstanceOf(EntryService.SaveResult.Saved.class);
  }
}
