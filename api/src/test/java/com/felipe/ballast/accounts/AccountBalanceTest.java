package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

// Saldo = saldo_inicial + receitas realizadas - despesas realizadas (ADR-024). Os lançamentos
// entram por SQL para o teste valer sem depender das rotas de lançamento.
@IntegrationTest
class AccountBalanceTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private AccountsApi api;
  private TestUsers.Registered user;
  private Cookie session;
  private UUID incomeCategory;
  private UUID expenseCategory;

  @BeforeEach
  void setUp() throws Exception {
    api = new AccountsApi(mvc);
    TestUsers users = new TestUsers(mvc);
    user = users.register();
    session = users.session(user);
    incomeCategory = insertCategory(user.id(), "receita");
    expenseCategory = insertCategory(user.id(), "despesa");
  }

  private UUID insertCategory(UUID userId, String type) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :userId, :name,"
                + " :type)")
        .param("id", id)
        .param("userId", userId)
        .param("name", "Teste " + id)
        .param("type", type)
        .update();
    return id;
  }

  private String createAccount(String name, long initialCents) throws Exception {
    var response =
        api.create(
            session,
            "{\"nome\":\""
                + name
                + "\",\"tipo\":\"corrente\",\"saldoInicialCentavos\":"
                + initialCents
                + "}");
    return AccountsApi.body(response).get("id").asString();
  }

  private void insertEntry(
      UUID userId, String accountId, UUID categoryId, String type, String amount) {
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,
                                    data_competencia, data_caixa)
            VALUES (:id, :userId, :type, 'teste', :categoryId, :accountId, :amount,
                    DATE '2026-03-10', DATE '2026-03-10')
            """)
        .param("id", UUID.randomUUID())
        .param("userId", userId)
        .param("type", type)
        .param("categoryId", categoryId)
        .param("accountId", UUID.fromString(accountId))
        .param("amount", new BigDecimal(amount))
        .update();
  }

  private long balanceFromFind(String accountId) throws Exception {
    return AccountsApi.body(api.find(session, accountId)).get("saldoCentavos").asLong();
  }

  private long balanceFromList(String accountId) throws Exception {
    for (JsonNode item : AccountsApi.body(api.list(session, ""))) {
      if (item.get("id").asString().equals(accountId)) {
        return item.get("saldoCentavos").asLong();
      }
    }
    throw new IllegalStateException("conta fora da lista");
  }

  private void assertBalance(String accountId, long expectedCents) throws Exception {
    assertThat(balanceFromList(accountId)).as("lista").isEqualTo(expectedCents);
    assertThat(balanceFromFind(accountId)).as("detalhe").isEqualTo(expectedCents);
  }

  @Test
  void anAccountWithoutEntriesKeepsTheInitialBalance() throws Exception {
    String account = createAccount("Sem lançamento", 123_45);

    assertBalance(account, 123_45);
  }

  @Test
  void incomeAddsAndExpenseSubtracts() throws Exception {
    String account = createAccount("Corrente", 1_000_00);
    insertEntry(user.id(), account, incomeCategory, "receita", "250.00");
    insertEntry(user.id(), account, expenseCategory, "despesa", "75.50");

    assertBalance(account, 1_000_00 + 250_00 - 75_50);
  }

  @Test
  void theBalanceCanGoNegative() throws Exception {
    String account = createAccount("Cheque especial", 100_00);
    insertEntry(user.id(), account, expenseCategory, "despesa", "300.00");

    assertBalance(account, -200_00);
  }

  @Test
  void centsAddUpExactly() throws Exception {
    String account = createAccount("Centavos", 1_000_00);
    insertEntry(user.id(), account, incomeCategory, "receita", "0.10");
    insertEntry(user.id(), account, incomeCategory, "receita", "0.20");
    insertEntry(user.id(), account, expenseCategory, "despesa", "0.30");

    assertBalance(account, 1_000_00);
  }

  @Test
  void onlyTheEntriesOfThatAccountCount() throws Exception {
    String first = createAccount("Primeira", 0);
    String second = createAccount("Segunda", 0);
    insertEntry(user.id(), first, incomeCategory, "receita", "10.00");
    insertEntry(user.id(), second, incomeCategory, "receita", "99.00");

    assertBalance(first, 10_00);
    assertBalance(second, 99_00);
  }

  @Test
  void anotherUsersEntriesNeverEnterTheBalance() throws Exception {
    String account = createAccount("Minha", 50_00);
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered other = users.register();
    Cookie otherSession = users.session(other);
    String otherAccount =
        AccountsApi.body(api.create(otherSession, "{\"nome\":\"Do outro\",\"tipo\":\"corrente\"}"))
            .get("id")
            .asString();
    insertEntry(
        other.id(), otherAccount, insertCategory(other.id(), "receita"), "receita", "1000.00");

    assertBalance(account, 50_00);
  }
}
