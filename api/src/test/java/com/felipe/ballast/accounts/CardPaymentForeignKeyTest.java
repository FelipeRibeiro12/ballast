package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

// A conferência da aplicação responde "elegível" para tudo, como numa corrida em que a conta de
// pagamento muda de dono ou de tipo entre a leitura e o INSERT. Só a FK composta da V10 segura.
@IntegrationTest
class CardPaymentForeignKeyTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired AccountRepository repository;
  @Autowired WriteTransaction transaction;

  private AccountService serviceThatTrustsEveryPaymentAccount() {
    AccountQueries alwaysEligible =
        new AccountQueries(jdbc) {
          @Override
          Optional<PaymentAccount> findPaymentAccount(UUID userId, UUID id) {
            return Optional.of(new PaymentAccount(true));
          }
        };
    return new AccountService(
        repository, alwaysEligible, new AccountDeletionQueries(jdbc), transaction);
  }

  private long countAccounts(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM conta WHERE usuario_id = :id")
        .param("id", userId)
        .query(Long.class)
        .single();
  }

  @Test
  void aPaymentAccountOfAnotherUserIsRejectedByTheDatabaseAndTheAccountRollsBack()
      throws Exception {
    AccountsApi api = new AccountsApi(mvc);
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered a = users.register();
    String ofB = api.createOk(users.session(users.register()), "Do B").get("id").asString();

    AccountService.CreateResult result =
        serviceThatTrustsEveryPaymentAccount()
            .create(
                a.id(),
                "Cartão",
                AccountType.CREDIT_CARD,
                BigDecimal.ZERO,
                new CardInput(new BigDecimal("1000.00"), 5, 10, UUID.fromString(ofB)),
                null);

    assertThat(result).isEqualTo(AccountService.CreateResult.Rejected.PAYMENT_ACCOUNT_NOT_FOUND);
    assertThat(countAccounts(a.id())).isZero();
  }

  @Test
  void aCashAccountAsPaymentIsRejectedByTheDatabase() throws Exception {
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered a = users.register();
    UUID cash = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo, saldo_inicial)"
                + " VALUES (:id, :userId, 'Carteira', 'dinheiro', 0)")
        .param("id", cash)
        .param("userId", a.id())
        .update();

    AccountService.CreateResult result =
        serviceThatTrustsEveryPaymentAccount()
            .create(
                a.id(),
                "Cartão",
                AccountType.CREDIT_CARD,
                BigDecimal.ZERO,
                new CardInput(new BigDecimal("1000.00"), 5, 10, cash),
                null);

    assertThat(result).isEqualTo(AccountService.CreateResult.Rejected.PAYMENT_ACCOUNT_NOT_FOUND);
    assertThat(countAccounts(a.id())).isEqualTo(1);
  }
}
