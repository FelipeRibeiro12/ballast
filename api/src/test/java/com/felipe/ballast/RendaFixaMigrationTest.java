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
class RendaFixaMigrationTest {

  @Autowired JdbcClient jdbc;

  @Test
  void acceptsContaInvestimentoWithZeroSaldoOnly() {
    UUID usuario = usuario();

    assertThat(contaSpec(usuario, "investimento", "0").update()).isEqualTo(1);
    assertConstraint("conta_investimento_sem_saldo", contaSpec(usuario, "investimento", "1.00"));
  }

  @Test
  void rejectsLancamentoInContaInvestimentoAndStillInCripto() {
    UUID usuario = usuario();

    assertConstraint(
        "lancamento_conta_recebe_lancamento", lancamento(usuario, conta(usuario, "investimento")));
    assertConstraint(
        "lancamento_conta_aceita_lancamento", lancamento(usuario, conta(usuario, "cripto")));
    assertThat(lancamento(usuario, conta(usuario, "corrente")).update()).isEqualTo(1);
  }

  @Test
  void acceptsATituloInAContaInvestimento() {
    UUID usuario = usuario();
    UUID conta = conta(usuario, "investimento");

    assertThat(titulo(usuario, conta, "lci", "cdi", "95", "2026-01-10", "2028-01-10").update())
        .isEqualTo(1);
    assertThat(titulo(usuario, conta, "cdb", "prefixado", "12.5", "2026-01-10", null).update())
        .isEqualTo(1);
    assertThat(titulo(usuario, conta, "lca", "ipca", "6", "2026-01-10", null).update())
        .isEqualTo(1);
  }

  @Test
  void rejectsATituloOutsideAContaInvestimentoOrOfAnotherUsuario() {
    UUID usuario = usuario();
    UUID outro = usuario();

    assertConstraint(
        "titulo_renda_fixa_conta_investimento_do_usuario",
        titulo(usuario, conta(usuario, "corrente"), "lci", "cdi", "95", "2026-01-10", null));
    assertConstraint(
        "titulo_renda_fixa_conta_investimento_do_usuario",
        titulo(usuario, conta(outro, "investimento"), "lci", "cdi", "95", "2026-01-10", null));
  }

  @Test
  void rejectsInvalidTituloValues() {
    UUID usuario = usuario();
    UUID conta = conta(usuario, "investimento");

    assertConstraint(
        "titulo_renda_fixa_tipo_valido",
        titulo(usuario, conta, "tesouro", "cdi", "95", "2026-01-10", null));
    assertConstraint(
        "titulo_renda_fixa_indexador_valido",
        titulo(usuario, conta, "lci", "selic", "95", "2026-01-10", null));
    assertConstraint(
        "titulo_renda_fixa_taxa_positiva",
        titulo(usuario, conta, "lci", "cdi", "0", "2026-01-10", null));
    assertConstraint(
        "titulo_renda_fixa_vencimento_depois",
        titulo(usuario, conta, "lci", "cdi", "95", "2026-01-10", "2026-01-10"));
  }

  @Test
  void resgateComesWithDateAndValueTogether() {
    UUID usuario = usuario();
    UUID conta = conta(usuario, "investimento");
    UUID titulo = tituloId(usuario, conta);

    assertConstraint(
        "titulo_renda_fixa_resgate_junto",
        jdbc.sql("UPDATE titulo_renda_fixa SET data_resgate = DATE '2026-05-01' WHERE id = :id")
            .param("id", titulo));
    assertConstraint(
        "titulo_renda_fixa_resgate_depois",
        jdbc.sql(
                "UPDATE titulo_renda_fixa SET data_resgate = DATE '2025-05-01',"
                    + " valor_resgate = 1 WHERE id = :id")
            .param("id", titulo));
  }

  @Test
  void marcacaoIsOnePerDayAndGoesAwayWithTheTitulo() {
    UUID usuario = usuario();
    UUID titulo = tituloId(usuario, conta(usuario, "investimento"));

    assertThat(marcacao(usuario, titulo, "2026-03-01", "1010.00").update()).isEqualTo(1);
    assertThatThrownBy(() -> marcacao(usuario, titulo, "2026-03-01", "1020.00").update())
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.sql("DELETE FROM titulo_renda_fixa WHERE id = :id").param("id", titulo).update();
    assertThat(
            jdbc.sql("SELECT count(*) FROM marcacao_renda_fixa WHERE titulo_id = :id")
                .param("id", titulo)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void indiceIsOnePerSerieAndDay() {
    // indice é global e o banco é compartilhado entre as classes: uma data que ninguém usa.
    String date = "1950-01-15";
    jdbc.sql("DELETE FROM indice WHERE serie = 'ipca' AND data = CAST(:d AS date)")
        .param("d", date)
        .update();

    assertThat(indice("ipca", date, "0.45").update()).isEqualTo(1);
    assertThatThrownBy(() -> indice("ipca", date, "0.50").update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertConstraint("indice_serie_valida", indice("selic", date, "0.1"));
  }

  @Test
  void deletingTheUsuarioClearsTitulosAndMarcacoes() {
    UUID usuario = usuario();
    UUID titulo = tituloId(usuario, conta(usuario, "investimento"));
    marcacao(usuario, titulo, "2026-03-01", "1010.00").update();

    jdbc.sql("DELETE FROM usuario WHERE id = :id").param("id", usuario).update();

    for (String table : new String[] {"titulo_renda_fixa", "marcacao_renda_fixa", "conta"}) {
      assertThat(
              jdbc.sql("SELECT count(*) FROM " + table + " WHERE usuario_id = :id")
                  .param("id", usuario)
                  .query(Long.class)
                  .single())
          .as(table)
          .isZero();
    }
  }

  private UUID usuario() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", id)
        .param("email", "renda-" + id + "@example.com")
        .update();
    return id;
  }

  private JdbcClient.StatementSpec contaSpec(UUID usuario, String tipo, String saldo) {
    UUID id = UUID.randomUUID();
    return jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo, saldo_inicial)"
                + " VALUES (:id, :usuario, :nome, :tipo, CAST(:saldo AS numeric))")
        .param("id", id)
        .param("usuario", usuario)
        .param("nome", tipo + "-" + id)
        .param("tipo", tipo)
        .param("saldo", saldo);
  }

  private UUID conta(UUID usuario, String tipo) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo, saldo_inicial)"
                + " VALUES (:id, :usuario, :nome, :tipo, 0)")
        .param("id", id)
        .param("usuario", usuario)
        .param("nome", tipo + "-" + id)
        .param("tipo", tipo)
        .update();
    return id;
  }

  private JdbcClient.StatementSpec lancamento(UUID usuario, UUID conta) {
    return jdbc.sql(
            "INSERT INTO lancamento (id, usuario_id, tipo, titulo, conta_id, valor,"
                + " data_competencia, data_caixa) VALUES (:id, :usuario, 'transferencia',"
                + " 'Teste', :conta, 10.00, DATE '2026-10-19', DATE '2026-10-19')")
        .param("id", UUID.randomUUID())
        .param("usuario", usuario)
        .param("conta", conta);
  }

  private JdbcClient.StatementSpec titulo(
      UUID usuario,
      UUID conta,
      String tipo,
      String indexador,
      String taxa,
      String aplicacao,
      String vencimento) {
    return jdbc.sql(
            "INSERT INTO titulo_renda_fixa (id, usuario_id, conta_id, tipo, nome, valor_aplicado,"
                + " data_aplicacao, vencimento, indexador, taxa) VALUES (:id, :usuario, :conta,"
                + " :tipo, 'Título', 1000.00, CAST(:aplicacao AS date), CAST(:vencimento AS date),"
                + " :indexador, CAST(:taxa AS numeric))")
        .param("id", UUID.randomUUID())
        .param("usuario", usuario)
        .param("conta", conta)
        .param("tipo", tipo)
        .param("aplicacao", aplicacao)
        .param("vencimento", vencimento, java.sql.Types.VARCHAR)
        .param("indexador", indexador)
        .param("taxa", taxa);
  }

  private UUID tituloId(UUID usuario, UUID conta) {
    titulo(usuario, conta, "lci", "cdi", "95", "2026-01-10", null).update();
    return jdbc.sql("SELECT id FROM titulo_renda_fixa WHERE conta_id = :c")
        .param("c", conta)
        .query(UUID.class)
        .single();
  }

  private JdbcClient.StatementSpec marcacao(UUID usuario, UUID titulo, String data, String valor) {
    return jdbc.sql(
            "INSERT INTO marcacao_renda_fixa (usuario_id, titulo_id, data, valor) VALUES"
                + " (:usuario, :titulo, CAST(:data AS date), CAST(:valor AS numeric))")
        .param("usuario", usuario)
        .param("titulo", titulo)
        .param("data", data)
        .param("valor", valor);
  }

  private JdbcClient.StatementSpec indice(String serie, String data, String valor) {
    return jdbc.sql(
            "INSERT INTO indice (serie, data, valor) VALUES (:serie, CAST(:data AS date),"
                + " CAST(:valor AS numeric))")
        .param("serie", serie)
        .param("data", data)
        .param("valor", valor);
  }

  private void assertConstraint(String constraint, JdbcClient.StatementSpec statement) {
    assertThatThrownBy(statement::update)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }
}
