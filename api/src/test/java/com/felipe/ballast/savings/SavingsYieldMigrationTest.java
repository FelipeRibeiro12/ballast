package com.felipe.ballast.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.felipe.ballast.TestcontainersConfiguration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SavingsYieldMigrationTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

  @Autowired JdbcClient jdbc;

  private UUID user() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", id)
        .param("email", "rc-" + id + "@example.com")
        .update();
    return id;
  }

  private UUID account(UUID user) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :u, :nome, 'cofrinho')")
        .param("id", id)
        .param("u", user)
        .param("nome", "C " + id)
        .update();
    return id;
  }

  private UUID entry(UUID user, UUID account) {
    UUID category = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :u, :nome, 'receita')")
        .param("id", category)
        .param("u", user)
        .param("nome", "K " + category)
        .update();
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,
                                    data_competencia, data_caixa, status)
            VALUES (:id, :u, 'receita', 'x', :cat, :acc, 1.00, :d, :d, 'realizado')
            """)
        .param("id", id)
        .param("u", user)
        .param("cat", category)
        .param("acc", account)
        .param("d", DAY)
        .update();
    return id;
  }

  private void marker(UUID user, UUID account, LocalDate day, UUID entry) {
    jdbc.sql(
            "INSERT INTO rendimento_cofrinho (id, usuario_id, conta_id, data, lancamento_id)"
                + " VALUES (:id, :u, :c, :d, :l)")
        .param("id", UUID.randomUUID())
        .param("u", user)
        .param("c", account)
        .param("d", day)
        .param("l", entry)
        .update();
  }

  @Test
  void rejectsASecondMarkerForTheSameAccountAndDay() {
    UUID user = user();
    UUID account = account(user);
    marker(user, account, DAY, null);

    assertThatThrownBy(() -> marker(user, account, DAY, null))
        .isInstanceOf(DataIntegrityViolationException.class);
    marker(user, account, DAY.plusDays(1), null);
  }

  @Test
  void rejectsAMarkerOnAnotherUsersAccount() {
    UUID owner = user();
    UUID account = account(owner);

    assertThatThrownBy(() -> marker(user(), account, DAY, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void rejectsAMarkerPointingToAnotherUsersEntry() {
    UUID owner = user();
    UUID otherUser = user();
    UUID entry = entry(otherUser, account(otherUser));

    assertThatThrownBy(() -> marker(owner, account(owner), DAY, entry))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deletingTheEntryKeepsTheMarkerAndTheOwner() {
    UUID user = user();
    UUID account = account(user);
    UUID entry = entry(user, account);
    marker(user, account, DAY, entry);

    jdbc.sql("DELETE FROM lancamento WHERE id = :id").param("id", entry).update();

    var row =
        jdbc.sql("SELECT usuario_id, lancamento_id FROM rendimento_cofrinho WHERE conta_id = :c")
            .param("c", account)
            .query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2)})
            .single();
    assertThat(row[0]).isEqualTo(user);
    assertThat(row[1]).isNull();
  }

  @Test
  void deletingTheAccountDeletesItsMarkers() {
    UUID user = user();
    UUID account = account(user);
    marker(user, account, DAY, null);

    jdbc.sql("DELETE FROM conta WHERE id = :id").param("id", account).update();

    assertThat(
            jdbc.sql("SELECT count(*) FROM rendimento_cofrinho WHERE usuario_id = :u")
                .param("u", user)
                .query(Long.class)
                .single())
        .isZero();
  }
}
