package com.felipe.ballast.recurrences;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.entries.EntryType;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.time.Clock;
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

// A conferência da aplicação responde "existe" para tudo, como numa corrida em que a conta ou a
// categoria muda de dono entre a leitura e o INSERT. Só as FKs compostas da V8 seguram.
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RecurrenceForeignKeyTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired WriteTransaction transaction;
  @Autowired Clock clock;
  private RecurrenceService service;
  private RecurrenceTestSupport db;
  private RecurrencesApi.Actor a;
  private RecurrencesApi.Actor b;

  @BeforeEach
  void setUp() throws Exception {
    RecurrencesApi api = new RecurrencesApi(mvc);
    db = new RecurrenceTestSupport(jdbc);
    a = api.newActor();
    b = api.newActor();
    RecurrenceQueries alwaysFound =
        new RecurrenceQueries(jdbc) {
          @Override
          Optional<Boolean> lockAccount(UUID userId, UUID accountId) {
            return Optional.of(false);
          }

          @Override
          boolean categoryExists(UUID userId, UUID categoryId, EntryType type) {
            return true;
          }
        };
    service = new RecurrenceService(alwaysFound, transaction, clock);
  }

  private RecurrenceInput input(EntryType type, String categoryId, String accountId) {
    RecurrenceRule rule =
        new RecurrenceRule(
            RecurrenceFrequency.MONTHLY, 1, 5, null, LocalDate.of(2026, 1, 1), null, null);
    return new RecurrenceInput(
        type,
        "Aluguel",
        null,
        new BigDecimal("1500.00"),
        UUID.fromString(categoryId),
        UUID.fromString(accountId),
        rule,
        null);
  }

  @Test
  void createWithAnotherUsersAccountIsRejectedByTheDatabase(CapturedOutput output) {
    RecurrenceService.CreateResult result =
        service.create(
            a.user().id(), input(EntryType.EXPENSE, a.expenseCategoryId(), b.accountId()));

    assertThat(result).isEqualTo(RecurrenceService.CreateResult.Rejected.ACCOUNT_NOT_FOUND);
    assertThat(db.countTemplates(a.user().id())).isZero();
    assertThat(db.countRules(a.user().id())).isZero();
    assertThat(output.getAll()).doesNotContain("foreign key").doesNotContain("Key (");
  }

  @Test
  void createWithAnotherUsersCategoryIsRejectedByTheDatabase(CapturedOutput output) {
    RecurrenceService.CreateResult result =
        service.create(
            a.user().id(), input(EntryType.EXPENSE, b.expenseCategoryId(), a.accountId()));

    assertThat(result).isEqualTo(RecurrenceService.CreateResult.Rejected.CATEGORY_NOT_FOUND);
    assertThat(db.countTemplates(a.user().id())).isZero();
    assertThat(db.countRules(a.user().id())).isZero();
    assertThat(output.getAll()).doesNotContain("foreign key").doesNotContain("Key (");
  }

  @Test
  void createWithACategoryOfTheOtherTypeIsRejectedByTheDatabase() {
    RecurrenceService.CreateResult result =
        service.create(
            a.user().id(), input(EntryType.EXPENSE, a.incomeCategoryId(), a.accountId()));

    assertThat(result).isEqualTo(RecurrenceService.CreateResult.Rejected.CATEGORY_NOT_FOUND);
    assertThat(db.countTemplates(a.user().id())).isZero();
  }
}
