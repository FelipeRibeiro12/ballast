package com.felipe.ballast.entries;

import static com.felipe.ballast.entries.EntriesApi.expenseBody;
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
// categoria muda de dono entre a leitura e o INSERT. Só as FKs compostas do banco seguram.
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class EntryForeignKeyTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired EntryRepository repository;
  @Autowired WriteTransaction transaction;
  private EntryService service;
  private EntriesApi.Actor a;
  private EntriesApi.Actor b;

  @BeforeEach
  void setUp() throws Exception {
    EntriesApi api = new EntriesApi(mvc);
    a = api.newActor();
    b = api.newActor();
    EntryQueries alwaysFound =
        new EntryQueries(jdbc) {
          @Override
          Optional<Boolean> lockAccount(UUID userId, UUID accountId) {
            return Optional.of(false);
          }

          @Override
          boolean categoryExists(UUID userId, UUID categoryId, EntryType type) {
            return true;
          }
        };
    service = new EntryService(repository, alwaysFound, transaction);
  }

  private EntryInput input(EntryType type, String categoryId, String accountId) {
    return new EntryInput(
        type,
        "teste",
        null,
        new BigDecimal("10.00"),
        UUID.fromString(categoryId),
        UUID.fromString(accountId),
        LocalDate.of(2026, 3, 10));
  }

  private long countEntries() {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :id")
        .param("id", a.user().id())
        .query(Long.class)
        .single();
  }

  @Test
  void createWithAnotherUsersAccountIsRejectedByTheDatabase(CapturedOutput output) {
    EntryService.SaveResult result =
        service.create(
            a.user().id(), input(EntryType.EXPENSE, a.expenseCategoryId(), b.accountId()));

    assertThat(result).isEqualTo(EntryService.SaveResult.Rejected.ACCOUNT_NOT_FOUND);
    assertThat(countEntries()).isZero();
    assertThat(output.getAll()).doesNotContain("foreign key").doesNotContain("Key (");
  }

  @Test
  void createWithAnotherUsersCategoryIsRejectedByTheDatabase(CapturedOutput output) {
    EntryService.SaveResult result =
        service.create(
            a.user().id(), input(EntryType.EXPENSE, b.expenseCategoryId(), a.accountId()));

    assertThat(result).isEqualTo(EntryService.SaveResult.Rejected.CATEGORY_NOT_FOUND);
    assertThat(countEntries()).isZero();
    assertThat(output.getAll()).doesNotContain("foreign key").doesNotContain("Key (");
  }

  @Test
  void createWithACategoryOfTheOtherTypeIsRejectedByTheDatabase() {
    EntryService.SaveResult result =
        service.create(
            a.user().id(), input(EntryType.EXPENSE, a.incomeCategoryId(), a.accountId()));

    assertThat(result).isEqualTo(EntryService.SaveResult.Rejected.CATEGORY_NOT_FOUND);
    assertThat(countEntries()).isZero();
  }

  @Test
  void updateWithAnotherUsersAccountOrCategoryIsRejectedByTheDatabaseAndChangesNothing(
      CapturedOutput output) throws Exception {
    JsonNode created = new EntriesApi(mvc).createOk(a.session(), expenseBody(a));
    UUID id = UUID.fromString(created.get("id").asString());

    EntryService.SaveResult account =
        service.update(
            a.user().id(), id, input(EntryType.EXPENSE, a.expenseCategoryId(), b.accountId()));
    EntryService.SaveResult category =
        service.update(
            a.user().id(), id, input(EntryType.EXPENSE, b.expenseCategoryId(), a.accountId()));
    EntryService.SaveResult otherType =
        service.update(
            a.user().id(), id, input(EntryType.EXPENSE, a.incomeCategoryId(), a.accountId()));

    assertThat(account).isEqualTo(EntryService.SaveResult.Rejected.ACCOUNT_NOT_FOUND);
    assertThat(category).isEqualTo(EntryService.SaveResult.Rejected.CATEGORY_NOT_FOUND);
    assertThat(otherType).isEqualTo(EntryService.SaveResult.Rejected.CATEGORY_NOT_FOUND);
    assertThat(EntriesApi.body(new EntriesApi(mvc).find(a.session(), id.toString())))
        .isEqualTo(created);
    assertThat(output.getAll()).doesNotContain("foreign key").doesNotContain("Key (");
  }
}
