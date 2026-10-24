package com.felipe.ballast.entries;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// A conferência da aplicação responde "existe" para tudo, como numa corrida em que a conta ou a
// categoria muda de dono (ou de tipo) entre a leitura e o INSERT. Só as FKs do banco seguram.
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class InstallmentForeignKeyTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired WriteTransaction transaction;
  @Autowired EntryRepository entryRepository;
  private InstallmentService service;
  private EntryService entryService;
  private InstallmentsApi api;
  private InstallmentsApi.Shopper a;
  private InstallmentsApi.Shopper b;

  @BeforeEach
  void setUp() throws Exception {
    api = new InstallmentsApi(mvc);
    a = api.newShopper();
    b = api.newShopper();
    EntryQueries alwaysFound =
        new EntryQueries(jdbc) {
          @Override
          Optional<Boolean> lockAccount(UUID userId, UUID accountId) {
            return Optional.of(false);
          }

          @Override
          Optional<CardBilling> findCardBilling(UUID userId, UUID accountId) {
            return Optional.of(new CardBilling(15, 25));
          }

          @Override
          boolean categoryExists(UUID userId, UUID categoryId, EntryType type) {
            return true;
          }

          // Esconde que é parcela, para o DELETE chegar à FK da V11.
          @Override
          Optional<LockedEntry> lockEntry(UUID userId, UUID entryId) {
            return super.lockEntry(userId, entryId)
                .map(
                    e ->
                        new LockedEntry(
                            e.title(),
                            e.categoryId(),
                            e.accountId(),
                            e.status(),
                            e.templateId(),
                            null,
                            e.competenceDate(),
                            e.cashDate()));
          }
        };
    service = new InstallmentService(new InstallmentQueries(jdbc), alwaysFound, transaction);
    entryService = new EntryService(entryRepository, alwaysFound, transaction);
  }

  private InstallmentInput input(String categoryId, String accountId) {
    return new InstallmentInput(
        "teste",
        null,
        new BigDecimal("30.00"),
        3,
        UUID.fromString(categoryId),
        UUID.fromString(accountId),
        LocalDate.of(2026, 3, 10));
  }

  private long countEntries() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :id")
        .param("id", a.actor().user().id())
        .query(Long.class)
        .single();
  }

  @Test
  void anotherUsersAccountIsRejectedByTheDatabase(CapturedOutput output) {
    InstallmentService.CreateResult result =
        service.create(a.actor().user().id(), input(a.actor().expenseCategoryId(), b.cardId()));

    assertThat(result).isEqualTo(InstallmentService.CreateResult.Rejected.ACCOUNT_NOT_FOUND);
    assertThat(countEntries()).isZero();
    assertThat(output.getAll()).doesNotContain("foreign key").doesNotContain("Key (");
  }

  @Test
  void aNonCardAccountOfTheUserIsRejectedByTheDatabase(CapturedOutput output) {
    InstallmentService.CreateResult result =
        service.create(
            a.actor().user().id(), input(a.actor().expenseCategoryId(), a.actor().accountId()));

    assertThat(result).isEqualTo(InstallmentService.CreateResult.Rejected.NOT_A_CARD);
    assertThat(countEntries()).isZero();
    assertThat(output.getAll()).doesNotContain("foreign key").doesNotContain("Key (");
  }

  @Test
  void anotherUsersCategoryAndAnIncomeCategoryAreRejectedByTheDatabase() {
    InstallmentService.CreateResult otherUser =
        service.create(a.actor().user().id(), input(b.actor().expenseCategoryId(), a.cardId()));
    InstallmentService.CreateResult income =
        service.create(a.actor().user().id(), input(a.actor().incomeCategoryId(), a.cardId()));

    assertThat(otherUser).isEqualTo(InstallmentService.CreateResult.Rejected.CATEGORY_NOT_FOUND);
    assertThat(income).isEqualTo(InstallmentService.CreateResult.Rejected.CATEGORY_NOT_FOUND);
    assertThat(countEntries()).isZero();
  }

  @Test
  void patchWithAnotherUsersCategoryIsRejectedByTheDatabaseAndChangesNothing() throws Exception {
    JsonNode purchase = api.createOk(a.session(), InstallmentsApi.purchaseBody(a));

    InstallmentService.UpdateResult result =
        service.update(
            a.actor().user().id(),
            UUID.fromString(purchase.get("id").asString()),
            new InstallmentService.Changes(
                null, false, null, UUID.fromString(b.actor().expenseCategoryId())));

    assertThat(result).isEqualTo(InstallmentService.UpdateResult.Rejected.CATEGORY_NOT_FOUND);
    assertThat(EntriesApi.body(api.find(a.session(), purchase.get("id").asString())))
        .isEqualTo(purchase);
  }

  @Test
  void deletingTheFirstInstallmentIsRejectedByTheForeignKeyEvenIfTheCheckIsBypassed()
      throws Exception {
    JsonNode purchase = api.createOk(a.session(), InstallmentsApi.purchaseBody(a));

    EntryService.DeleteResult result =
        entryService.delete(a.actor().user().id(), UUID.fromString(purchase.get("id").asString()));

    assertThat(result).isEqualTo(EntryService.DeleteResult.FIRST_INSTALLMENT);
    assertThat(countEntries()).isEqualTo(3);
  }
}
