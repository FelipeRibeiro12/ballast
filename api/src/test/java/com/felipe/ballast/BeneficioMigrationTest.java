package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BeneficioMigrationTest {

  private static final String GATILHO = "lancamento_categoria_permitida_no_beneficio";

  @Autowired JdbcClient jdbc;

  @Test
  void acceptsTheBeneficioTipo() {
    Fixture f = fixture();

    assertThat(f.beneficio).isNotNull();
  }

  @Test
  void rejectsPermitidaForAContaThatIsNotBeneficio() {
    Fixture f = fixture();

    assertConstraint(
        "conta_categoria_permitida_conta_beneficio_do_usuario",
        permitida(f.usuario, f.corrente, f.comida));
  }

  @Test
  void rejectsPermitidaWithAReceitaCategoria() {
    Fixture f = fixture();

    assertConstraint(
        "conta_categoria_permitida_categoria_despesa_do_usuario",
        permitida(f.usuario, f.beneficio, f.salario));
  }

  @Test
  void rejectsPermitidaWithContaOrCategoriaOfAnotherUsuario() {
    Fixture f = fixture();
    Fixture other = fixture();

    assertConstraint(
        "conta_categoria_permitida_conta_beneficio_do_usuario",
        permitida(f.usuario, other.beneficio, f.comida));
    assertConstraint(
        "conta_categoria_permitida_categoria_despesa_do_usuario",
        permitida(f.usuario, f.beneficio, other.comida));
  }

  @Test
  void rejectsTheSameCategoriaTwiceInTheList() {
    Fixture f = fixture();
    permitida(f.usuario, f.beneficio, f.comida).update();

    assertConstraint("conta_categoria_permitida_pkey", permitida(f.usuario, f.beneficio, f.comida));
  }

  @Test
  void acceptsAnyDespesaCategoriaWhenTheListIsEmpty() {
    Fixture f = fixture();

    assertThat(lancamento(f, "despesa", f.beneficio, f.comida).update()).isEqualTo(1);
    assertThat(lancamento(f, "despesa", f.beneficio, f.lazer).update()).isEqualTo(1);
  }

  @Test
  void rejectsDespesaOutsideTheListAndAcceptsInside() {
    Fixture f = fixture();
    permitida(f.usuario, f.beneficio, f.comida).update();

    assertConstraint(GATILHO, lancamento(f, "despesa", f.beneficio, f.lazer));
    assertThat(lancamento(f, "despesa", f.beneficio, f.comida).update()).isEqualTo(1);
  }

  @Test
  void raisesCheckViolationWithTheConstraintName() {
    Fixture f = fixture();
    permitida(f.usuario, f.beneficio, f.comida).update();

    assertThatThrownBy(() -> lancamento(f, "despesa", f.beneficio, f.lazer).update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
        .rootCause()
        .satisfies(
            e -> {
              var erro = ((org.postgresql.util.PSQLException) e).getServerErrorMessage();
              assertThat(erro.getSQLState()).isEqualTo("23514");
              assertThat(erro.getConstraint()).isEqualTo(GATILHO);
            });
  }

  @Test
  void acceptsReceitaWithAnyCategoriaEvenWithAList() {
    Fixture f = fixture();
    permitida(f.usuario, f.beneficio, f.comida).update();

    assertThat(lancamento(f, "receita", f.beneficio, f.salario).update()).isEqualTo(1);
  }

  @Test
  void doesNotRestrictOtherContaTipos() {
    Fixture f = fixture();
    permitida(f.usuario, f.beneficio, f.comida).update();

    assertThat(lancamento(f, "despesa", f.corrente, f.lazer).update()).isEqualTo(1);
  }

  @Test
  void rejectsUpdateThatMovesTheCategoriaOutsideTheList() {
    Fixture f = fixture();
    permitida(f.usuario, f.beneficio, f.comida).update();
    UUID id = UUID.randomUUID();
    lancamento(f, id, "despesa", f.beneficio, f.comida).update();

    assertConstraint(
        GATILHO,
        jdbc.sql("UPDATE lancamento SET categoria_id = :categoria WHERE id = :id")
            .param("categoria", f.lazer)
            .param("id", id));
  }

  @Test
  void rejectsUpdateThatMovesTheLancamentoToABeneficioWithTheCategoriaOutsideTheList() {
    Fixture f = fixture();
    permitida(f.usuario, f.beneficio, f.comida).update();
    UUID id = UUID.randomUUID();
    lancamento(f, id, "despesa", f.corrente, f.lazer).update();

    assertConstraint(
        GATILHO,
        jdbc.sql("UPDATE lancamento SET conta_id = :conta WHERE id = :id")
            .param("conta", f.beneficio)
            .param("id", id));
  }

  @Test
  void doesNotRevalidateAnOldLancamentoWhenOnlyTheTituloChanges() {
    Fixture f = fixture();
    UUID id = UUID.randomUUID();
    lancamento(f, id, "despesa", f.beneficio, f.lazer).update();
    permitida(f.usuario, f.beneficio, f.comida).update();

    int updated =
        jdbc.sql("UPDATE lancamento SET titulo = 'Outro' WHERE id = :id").param("id", id).update();

    assertThat(updated).isEqualTo(1);
  }

  @Test
  void refusesToDeleteACategoriaThatIsInTheList() {
    Fixture f = fixture();
    permitida(f.usuario, f.beneficio, f.comida).update();

    assertConstraint(
        "conta_categoria_permitida_categoria_despesa_do_usuario",
        jdbc.sql("DELETE FROM categoria WHERE id = :id").param("id", f.comida));
  }

  @Test
  void deletesTheListWithTheConta() {
    Fixture f = fixture();
    permitida(f.usuario, f.beneficio, f.comida).update();

    jdbc.sql("DELETE FROM conta WHERE id = :id").param("id", f.beneficio).update();

    assertThat(count("conta_categoria_permitida", f.usuario)).isZero();
  }

  @Test
  void deletesEverythingWhenTheUsuarioIsDeleted() {
    Fixture f = fixture();
    permitida(f.usuario, f.beneficio, f.comida).update();
    lancamento(f, "despesa", f.beneficio, f.comida).update();

    int deleted = jdbc.sql("DELETE FROM usuario WHERE id = :id").param("id", f.usuario).update();

    assertThat(deleted).isEqualTo(1);
    assertThat(count("conta_categoria_permitida", f.usuario)).isZero();
    assertThat(count("lancamento", f.usuario)).isZero();
  }

  private record Fixture(
      UUID usuario, UUID corrente, UUID beneficio, UUID comida, UUID lazer, UUID salario) {}

  private Fixture fixture() {
    UUID usuario = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuario)
        .param("email", "beneficio-" + usuario + "@example.com")
        .update();
    return new Fixture(
        usuario,
        conta(usuario, "corrente"),
        conta(usuario, "beneficio"),
        categoria(usuario, "Comida", "despesa"),
        categoria(usuario, "Lazer", "despesa"),
        categoria(usuario, "Salario", "receita"));
  }

  private UUID conta(UUID usuario, String tipo) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuario, :nome, :tipo)")
        .param("id", id)
        .param("usuario", usuario)
        .param("nome", tipo + "-" + id)
        .param("tipo", tipo)
        .update();
    return id;
  }

  private UUID categoria(UUID usuario, String nome, String tipo) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :usuario, :nome,"
                + " :tipo)")
        .param("id", id)
        .param("usuario", usuario)
        .param("nome", nome)
        .param("tipo", tipo)
        .update();
    return id;
  }

  // o usuario_id é o informado: os testes de outro dono passam recurso alheio de propósito
  private JdbcClient.StatementSpec permitida(UUID usuario, UUID conta, UUID categoria) {
    return jdbc.sql(
            "INSERT INTO conta_categoria_permitida (conta_id, categoria_id, usuario_id)"
                + " VALUES (:conta, :categoria, :usuario)")
        .param("conta", conta)
        .param("categoria", categoria)
        .param("usuario", usuario);
  }

  private JdbcClient.StatementSpec lancamento(Fixture f, String tipo, UUID conta, UUID categoria) {
    return lancamento(f, UUID.randomUUID(), tipo, conta, categoria);
  }

  private JdbcClient.StatementSpec lancamento(
      Fixture f, UUID id, String tipo, UUID conta, UUID categoria) {
    return jdbc.sql(
            "INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,"
                + " data_competencia, data_caixa) VALUES (:id, :usuario, :tipo, 'Almoço',"
                + " :categoria, :conta, 10.00, DATE '2026-10-20', DATE '2026-10-20')")
        .param("id", id)
        .param("usuario", f.usuario)
        .param("tipo", tipo)
        .param("categoria", categoria)
        .param("conta", conta);
  }

  private long count(String table, UUID usuario) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE usuario_id = :id")
        .param("id", usuario)
        .query(Long.class)
        .single();
  }

  private void assertConstraint(String constraint, JdbcClient.StatementSpec statement) {
    assertThatThrownBy(statement::update)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }
}
