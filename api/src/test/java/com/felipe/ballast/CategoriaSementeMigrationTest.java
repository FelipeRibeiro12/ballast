package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

// V24 (semente marcada por coluna) e V25 (filha com o tipo do pai), ADR-057.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CategoriaSementeMigrationTest {

  @Autowired JdbcClient jdbc;

  @Test
  void marksTheSeedsByNameAndPromotesChildrenWithAnotherTypeWhenMigratingFromV23() {
    try (PostgreSQLContainer own = TestcontainersConfiguration.newPostgres()) {
      own.start();
      flyway(own, "23").migrate();
      JdbcClient owner =
          JdbcClient.create(
              new DriverManagerDataSource(own.getJdbcUrl(), own.getUsername(), own.getPassword()));
      UUID user = UUID.randomUUID();
      owner
          .sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
          .param("id", user)
          .param("email", "semente@example.com")
          .update();
      UUID salary = category(owner, user, "Salário", "receita", null);
      UUID others = category(owner, user, "Outros", "despesa", null);
      UUID otherIncome = category(owner, user, "Outras receitas", "receita", null);
      UUID market = category(owner, user, "Mercado", "despesa", null);
      // Filha de despesa sob pai de receita: o dado que a V25 passa a recusar. Uma tem nome igual
      // a uma raiz, para a promoção a raiz não colidir.
      UUID bonus = category(owner, user, "Bônus", "despesa", salary);
      UUID sameName = category(owner, user, "Mercado", "despesa", salary);
      UUID account = UUID.randomUUID();
      owner
          .sql(
              "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :u, 'Conta',"
                  + " 'corrente')")
          .param("id", account)
          .param("u", user)
          .update();
      owner
          .sql(
              "INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id,"
                  + " valor, data_competencia, data_caixa) VALUES (:id, :u, 'despesa', 'x', :c,"
                  + " :a, 10, DATE '2026-10-01', DATE '2026-10-01')")
          .param("id", UUID.randomUUID())
          .param("u", user)
          .param("c", bonus)
          .param("a", account)
          .update();

      flyway(own, null).migrate();

      assertThat(seedOf(owner, salary)).isEqualTo("salario");
      assertThat(seedOf(owner, others)).isEqualTo("outros");
      assertThat(seedOf(owner, otherIncome)).isEqualTo("outras_receitas");
      assertThat(seedOf(owner, market)).isNull();
      assertThat(parentOf(owner, bonus)).isNull();
      assertThat(parentOf(owner, sameName)).isNull();
      assertThat(
              owner
                  .sql("SELECT nome FROM categoria WHERE id = :id")
                  .param("id", sameName)
                  .query(String.class)
                  .single())
          .isEqualTo("Mercado (raiz)");
      assertThat(
              owner
                  .sql("SELECT count(*) FROM lancamento WHERE categoria_id = :id")
                  .param("id", bonus)
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
    }
  }

  @Test
  void rejectsASecondSeedOfTheSameKindForTheUser() {
    UUID user = user();
    category(jdbc, user, "Salário", "receita", null, "salario");

    assertConstraint(
        "categoria_semente_unica",
        () -> category(jdbc, user, "Ordenado", "receita", null, "salario"));
    UUID other = user();
    category(jdbc, other, "Salário", "receita", null, "salario");
  }

  @Test
  void rejectsASeedOutsideTheListAWrongTypeOrAChild() {
    UUID user = user();
    UUID salary = category(jdbc, user, "Salário", "receita", null, "salario");

    assertConstraint(
        "categoria_semente_valida", () -> category(jdbc, user, "A", "receita", null, "outra"));
    assertConstraint(
        "categoria_semente_tipo", () -> category(jdbc, user, "B", "despesa", null, "salario"));
    assertConstraint(
        "categoria_semente_so_na_raiz",
        () -> category(jdbc, user, "C", "receita", salary, "outras_receitas"));
  }

  @Test
  void rejectsAChildWithADifferentTypeThanItsParent() {
    UUID user = user();
    UUID income = category(jdbc, user, "Pai receita", "receita", null);

    assertConstraint(
        "categoria_pai_mesmo_tipo", () -> category(jdbc, user, "Filha", "despesa", income));
    category(jdbc, user, "Filha boa", "receita", income);
  }

  @Test
  void refusesToChangeTheTypeOfAParentThatHasChildren() {
    UUID user = user();
    UUID parent = category(jdbc, user, "Pai", "receita", null);
    category(jdbc, user, "Filha", "receita", parent);

    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE categoria SET tipo = 'despesa' WHERE id = :id")
                    .param("id", parent)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private UUID user() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", id)
        .param("email", "cat-" + id + "@example.com")
        .update();
    return id;
  }

  private static UUID category(
      JdbcClient client, UUID user, String name, String type, UUID parent) {
    return category(client, user, name, type, parent, null);
  }

  private static UUID category(
      JdbcClient client, UUID user, String name, String type, UUID parent, String seed) {
    UUID id = UUID.randomUUID();
    // A coluna semente só existe depois da V24; no banco antigo o INSERT não a cita.
    boolean withSeed = seed != null;
    client
        .sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo, pai_id"
                + (withSeed ? ", semente" : "")
                + ") VALUES (:id, :u, :nome, :tipo, :pai"
                + (withSeed ? ", :semente" : "")
                + ")")
        .params(
            withSeed
                ? Map.of("id", id, "u", user, "nome", name, "tipo", type, "semente", seed)
                : Map.of("id", id, "u", user, "nome", name, "tipo", type))
        .param("pai", parent, java.sql.Types.OTHER)
        .update();
    return id;
  }

  private static String seedOf(JdbcClient owner, UUID id) {
    return owner
        .sql("SELECT semente FROM categoria WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private static UUID parentOf(JdbcClient owner, UUID id) {
    return owner
        .sql("SELECT pai_id FROM categoria WHERE id = :id")
        .param("id", id)
        .query(UUID.class)
        .optional()
        .orElse(null);
  }

  private static Flyway flyway(PostgreSQLContainer container, String target) {
    var config =
        Flyway.configure()
            .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
            .locations("classpath:db/migration")
            .placeholders(Map.of("app_user", TestcontainersConfiguration.APP_USER));
    if (target != null) {
      config.target(target);
    }
    return config.load();
  }

  private static void assertConstraint(String constraint, Runnable statement) {
    assertThatThrownBy(statement::run)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }
}
