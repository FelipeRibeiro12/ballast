package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MigrationsTest {

  @Autowired JdbcClient jdbc;
  @Autowired PostgreSQLContainer postgres;

  @Test
  void createsTablesOnEmptyDatabase() {
    List<String> tables =
        jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
            .query(String.class)
            .list();

    assertThat(tables)
        .contains(
            "health_check",
            "usuario",
            "sessao",
            "tentativa_login",
            "conta",
            "categoria",
            "lancamento",
            "transferencia");
  }

  @Test
  void appliesAllMigrationsInOrder() {
    // o papel da aplicação não lê o histórico (V4), então a consulta vai como dono
    JdbcClient owner =
        JdbcClient.create(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    List<String> versions =
        owner
            .sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
            .query(String.class)
            .list();

    assertThat(versions)
        .containsExactly(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16",
            "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30",
            "31", "32", "33", "34", "35", "36", "37", "38", "39", "40", "41", "42");
  }

  @Test
  void rejectsTipoOutsideTheAllowedList() {
    assertConstraint("conta_tipo_valido", insertConta(newUsuario(), "A", "cheque", "BRL", "0"));
  }

  @Test
  void acceptsCartaoCreditoBeneficioAndCripto() {
    assertThat(insertConta(newUsuario(), "Cartão", "cartao_credito", "BRL", "0").update())
        .isEqualTo(1);
    assertThat(insertConta(newUsuario(), "VR", "beneficio", "BRL", "0").update()).isEqualTo(1);
    assertThat(insertConta(newUsuario(), "Carteira", "cripto", "BRL", "0").update()).isEqualTo(1);
  }

  @Test
  void rejectsMoedaOtherThanBrl() {
    assertConstraint("conta_moeda_brl", insertConta(newUsuario(), "A", "corrente", "USD", "0"));
  }

  @Test
  void rejectsEmptyNome() {
    assertConstraint("conta_nome_tamanho", insertConta(newUsuario(), "", "corrente", "BRL", "0"));
  }

  @Test
  void rejectsNomeLongerThan100Characters() {
    assertConstraint(
        "conta_nome_tamanho", insertConta(newUsuario(), "a".repeat(101), "corrente", "BRL", "0"));
  }

  @Test
  void acceptsNomeWith100Characters() {
    int inserted = insertConta(newUsuario(), "a".repeat(100), "corrente", "BRL", "0").update();

    assertThat(inserted).isEqualTo(1);
  }

  @Test
  void rejectsNomeWithSpaceAtTheEdges() {
    UUID usuarioId = newUsuario();

    assertConstraint(
        "conta_nome_sem_espaco_nas_pontas",
        insertConta(usuarioId, " Nubank", "corrente", "BRL", "0"));
    assertConstraint(
        "conta_nome_sem_espaco_nas_pontas",
        insertConta(usuarioId, "Nubank ", "corrente", "BRL", "0"));
  }

  @Test
  void rejectsSaldoInicialAboveTheCeilingAndBelowTheFloor() {
    UUID usuarioId = newUsuario();

    assertConstraint(
        "conta_saldo_inicial_faixa",
        insertConta(usuarioId, "Alto", "corrente", "BRL", "90071992547409.92"));
    assertConstraint(
        "conta_saldo_inicial_faixa",
        insertConta(usuarioId, "Baixo", "corrente", "BRL", "-90071992547409.92"));
  }

  @Test
  void acceptsSaldoInicialAtTheLimits() {
    UUID usuarioId = newUsuario();

    assertThat(insertConta(usuarioId, "Teto", "corrente", "BRL", "90071992547409.91").update())
        .isEqualTo(1);
    assertThat(insertConta(usuarioId, "Piso", "corrente", "BRL", "-90071992547409.91").update())
        .isEqualTo(1);
  }

  @Test
  void rejectsSameNomeWithDifferentCaseForTheSameUsuario() {
    UUID usuarioId = newUsuario();
    insertConta(usuarioId, "Nubank", "corrente", "BRL", "0").update();

    assertConstraint(
        "conta_usuario_nome_unico", insertConta(usuarioId, "NUBANK", "poupanca", "BRL", "0"));
  }

  @Test
  void acceptsSameNomeForDifferentUsuarios() {
    insertConta(newUsuario(), "Nubank", "corrente", "BRL", "0").update();

    assertThat(insertConta(newUsuario(), "Nubank", "corrente", "BRL", "0").update()).isEqualTo(1);
  }

  private UUID newUsuario() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", id)
        .param("email", "conta-" + id + "@example.com")
        .update();
    return id;
  }

  private JdbcClient.StatementSpec insertConta(
      UUID usuarioId, String nome, String tipo, String moeda, String saldoInicial) {
    return jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo, moeda, saldo_inicial)"
                + " VALUES (:id, :usuarioId, :nome, :tipo, :moeda, CAST(:saldo AS numeric))")
        .param("id", UUID.randomUUID())
        .param("usuarioId", usuarioId)
        .param("nome", nome)
        .param("tipo", tipo)
        .param("moeda", moeda)
        .param("saldo", saldoInicial);
  }

  private void assertConstraint(String constraint, JdbcClient.StatementSpec insert) {
    assertThatThrownBy(insert::update)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }
}
