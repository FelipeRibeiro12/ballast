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
class TransferenciaMigrationTest {

  @Autowired JdbcClient jdbc;

  @Test
  void acceptsTransferenciaLancamentoWithoutCategoriaAndRejectsWithOne() {
    Fixture f = fixture();

    assertThat(insertLancamento(f, "transferencia", null, f.contaA).update()).isEqualTo(1);
    assertConstraint(
        "lancamento_categoria_conforme_tipo",
        insertLancamento(f, "transferencia", f.categoria, f.contaA));
  }

  @Test
  void rejectsReceitaAndDespesaWithoutCategoria() {
    Fixture f = fixture();

    assertConstraint(
        "lancamento_categoria_conforme_tipo", insertLancamento(f, "receita", null, f.contaA));
    assertConstraint(
        "lancamento_categoria_conforme_tipo", insertLancamento(f, "despesa", null, f.contaA));
  }

  @Test
  void acceptsAValidPair() {
    Fixture f = fixture();
    UUID saida = perna(f, f.contaA);
    UUID entrada = perna(f, f.contaB);

    assertThat(insertTransferencia(f, saida, entrada, f.contaA, f.contaB).update()).isEqualTo(1);
  }

  @Test
  void rejectsPernasOfAnotherUsuario() {
    Fixture f = fixture();
    Fixture other = fixture();
    UUID saidaAlheia = perna(other, other.contaA);
    UUID entrada = perna(f, f.contaB);
    UUID saida = perna(f, f.contaA);
    UUID entradaAlheia = perna(other, other.contaB);

    assertConstraint(
        "transferencia_saida_perna_valida",
        insertTransferencia(f, saidaAlheia, entrada, other.contaA, f.contaB));
    assertConstraint(
        "transferencia_entrada_perna_valida",
        insertTransferencia(f, saida, entradaAlheia, f.contaA, other.contaB));
  }

  @Test
  void rejectsPernaThatIsNotTransferenciaTipo() {
    Fixture f = fixture();
    UUID receita = UUID.randomUUID();
    insertLancamento(receita, f, "receita", f.categoria, f.contaA).update();
    UUID entrada = perna(f, f.contaB);

    assertConstraint(
        "transferencia_saida_perna_valida",
        insertTransferencia(f, receita, entrada, f.contaA, f.contaB));
  }

  @Test
  void rejectsSaidaEqualToEntrada() {
    Fixture f = fixture();
    UUID perna = perna(f, f.contaA);

    assertConstraint(
        "transferencia_pernas_diferentes",
        insertTransferencia(f, perna, perna, f.contaA, f.contaB));
  }

  @Test
  void rejectsBothPernasInTheSameConta() {
    Fixture f = fixture();
    UUID saida = perna(f, f.contaA);
    UUID entrada = perna(f, f.contaA);

    assertConstraint(
        "transferencia_contas_diferentes",
        insertTransferencia(f, saida, entrada, f.contaA, f.contaA));
  }

  @Test
  void rejectsDeclaredContaThatDiffersFromThePerna() {
    Fixture f = fixture();
    // as duas pernas estão na conta A, mas a transferencia diz A e B
    UUID saida = perna(f, f.contaA);
    UUID entrada = perna(f, f.contaA);

    assertConstraint(
        "transferencia_entrada_perna_valida",
        insertTransferencia(f, saida, entrada, f.contaA, f.contaB));
  }

  @Test
  void rejectsLancamentoInTwoTransferencias() {
    Fixture f = fixture();
    UUID saida = perna(f, f.contaA);
    UUID entrada = perna(f, f.contaB);
    insertTransferencia(f, saida, entrada, f.contaA, f.contaB).update();

    // mesma saida de novo
    assertConstraint(
        "transferencia_saida_unica",
        insertTransferencia(f, saida, perna(f, f.contaB), f.contaA, f.contaB));
    // mesma entrada de novo
    assertConstraint(
        "transferencia_entrada_unica",
        insertTransferencia(f, perna(f, f.contaA), entrada, f.contaA, f.contaB));
    // a entrada de uma como saida de outra (papéis diferentes)
    assertConstraint(
        "transferencia_perna_em_dois_papeis",
        insertTransferencia(f, entrada, perna(f, f.contaA), f.contaB, f.contaA));
    // e a saida de uma como entrada de outra
    assertConstraint(
        "transferencia_perna_em_dois_papeis",
        insertTransferencia(f, perna(f, f.contaB), saida, f.contaB, f.contaA));
  }

  @Test
  void refusesToDeletePernaWhileTheLinkExists() {
    Fixture f = fixture();
    UUID saida = perna(f, f.contaA);
    UUID entrada = perna(f, f.contaB);
    UUID id = UUID.randomUUID();
    insertTransferencia(id, f, saida, entrada, f.contaA, f.contaB).update();

    assertConstraint(
        "transferencia_saida_perna_valida",
        jdbc.sql("DELETE FROM lancamento WHERE id = :id").param("id", saida));
    assertConstraint(
        "transferencia_entrada_perna_valida",
        jdbc.sql("DELETE FROM lancamento WHERE id = :id").param("id", entrada));
    // mudar a conta de uma perna também quebraria o par
    assertConstraint(
        "transferencia_saida_perna_valida",
        jdbc.sql("UPDATE lancamento SET conta_id = :conta WHERE id = :id")
            .param("conta", f.contaB)
            .param("id", saida));

    // sem a ligação, apagar passa; apagar a ligação não apaga as pernas
    assertThat(jdbc.sql("DELETE FROM transferencia WHERE id = :id").param("id", id).update())
        .isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT count(*) FROM lancamento WHERE id IN (:a, :b)")
                .param("a", saida)
                .param("b", entrada)
                .query(Long.class)
                .single())
        .isEqualTo(2);
    assertThat(jdbc.sql("DELETE FROM lancamento WHERE id = :id").param("id", saida).update())
        .isEqualTo(1);
  }

  @Test
  void deletesEverythingWhenTheUsuarioIsDeleted() {
    Fixture f = fixture();
    insertTransferencia(f, perna(f, f.contaA), perna(f, f.contaB), f.contaA, f.contaB).update();

    int deleted = jdbc.sql("DELETE FROM usuario WHERE id = :id").param("id", f.usuario).update();

    assertThat(deleted).isEqualTo(1);
    assertThat(count("transferencia", f.usuario)).isZero();
    assertThat(count("lancamento", f.usuario)).isZero();
    assertThat(count("conta", f.usuario)).isZero();
  }

  private record Fixture(UUID usuario, UUID contaA, UUID contaB, UUID categoria) {}

  private Fixture fixture() {
    UUID usuario = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuario)
        .param("email", "transf-" + usuario + "@example.com")
        .update();
    UUID contaA = conta(usuario, "Conta A");
    UUID contaB = conta(usuario, "Conta B");
    UUID categoria = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :usuario,"
                + " 'Teste despesa', 'despesa')")
        .param("id", categoria)
        .param("usuario", usuario)
        .update();
    return new Fixture(usuario, contaA, contaB, categoria);
  }

  private UUID conta(UUID usuario, String nome) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuario, :nome,"
                + " 'dinheiro')")
        .param("id", id)
        .param("usuario", usuario)
        .param("nome", nome)
        .update();
    return id;
  }

  private UUID perna(Fixture f, UUID conta) {
    UUID id = UUID.randomUUID();
    insertLancamento(id, f, "transferencia", null, conta).update();
    return id;
  }

  private JdbcClient.StatementSpec insertLancamento(
      Fixture f, String tipo, UUID categoria, UUID conta) {
    return insertLancamento(UUID.randomUUID(), f, tipo, categoria, conta);
  }

  private JdbcClient.StatementSpec insertLancamento(
      UUID id, Fixture f, String tipo, UUID categoria, UUID conta) {
    // categoria da receita precisa ser de receita, então o teste de receita usa uma própria
    UUID categoriaFinal = categoria;
    if (categoria != null && tipo.equals("receita")) {
      categoriaFinal = UUID.randomUUID();
      jdbc.sql(
              "INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :usuario,"
                  + " 'Teste receita', 'receita') ON CONFLICT DO NOTHING")
          .param("id", categoriaFinal)
          .param("usuario", f.usuario)
          .update();
    }
    return jdbc.sql(
            "INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,"
                + " data_competencia, data_caixa) VALUES (:id, :usuario, :tipo, 'Teste',"
                + " :categoria, :conta, 10.00, DATE '2026-10-19', DATE '2026-10-19')")
        .param("id", id)
        .param("usuario", f.usuario)
        .param("tipo", tipo)
        .param("categoria", categoriaFinal, java.sql.Types.OTHER)
        .param("conta", conta);
  }

  private JdbcClient.StatementSpec insertTransferencia(
      Fixture f, UUID saida, UUID entrada, UUID contaSaida, UUID contaEntrada) {
    return insertTransferencia(UUID.randomUUID(), f, saida, entrada, contaSaida, contaEntrada);
  }

  private JdbcClient.StatementSpec insertTransferencia(
      UUID id, Fixture f, UUID saida, UUID entrada, UUID contaSaida, UUID contaEntrada) {
    return jdbc.sql(
            "INSERT INTO transferencia (id, usuario_id, saida_id, entrada_id, conta_saida_id,"
                + " conta_entrada_id) VALUES (:id, :usuario, :saida, :entrada, :contaSaida,"
                + " :contaEntrada)")
        .param("id", id)
        .param("usuario", f.usuario)
        .param("saida", saida)
        .param("entrada", entrada)
        .param("contaSaida", contaSaida)
        .param("contaEntrada", contaEntrada);
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
