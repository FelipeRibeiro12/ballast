package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ParcelaMigrationTest {

  @Autowired JdbcClient jdbc;

  @Test
  void acceptsAFullCompraInTheCartao() {
    Fixture f = fixture();
    UUID first = compra(f, 3);

    assertThat(count("parcela_de_id", first)).isEqualTo(3);
    assertThat(
            jdbc.sql("SELECT parcela_tipo_conta FROM lancamento WHERE id = :id")
                .param("id", first)
                .query(String.class)
                .single())
        .isEqualTo("cartao_credito");
  }

  @Test
  void acceptsTheLimitsOfTotalAndNum() {
    Fixture f = fixture();

    compra(f, 2);
    compra(f, 48);
  }

  @Test
  void leavesNonParcelaLancamentoUntouchedInAnyConta() {
    Fixture f = fixture();

    assertThat(parcela(f, f.corrente, null, null, null).update()).isEqualTo(1);
    assertThat(parcela(f, f.cartao, null, null, null).update()).isEqualTo(1);
  }

  @Test
  void rejectsPartiallyFilledColumns() {
    Fixture f = fixture();
    UUID first = compra(f, 3);

    assertConstraint("lancamento_parcela_colunas_juntas", parcela(f, f.cartao, first, null, null));
    assertConstraint("lancamento_parcela_colunas_juntas", parcela(f, f.cartao, null, 2, 3));
    assertConstraint("lancamento_parcela_colunas_juntas", parcela(f, f.cartao, null, null, 3));
    assertConstraint("lancamento_parcela_colunas_juntas", parcela(f, f.cartao, first, 2, null));
    assertConstraint("lancamento_parcela_colunas_juntas", parcela(f, f.cartao, first, null, 3));
  }

  @Test
  void rejectsTotalOutsideTwoToFortyEight() {
    Fixture f = fixture();

    for (int total : new int[] {1, 49}) {
      UUID id = UUID.randomUUID();
      assertConstraint(
          "lancamento_parcela_total_faixa", parcela(f, f.cartao, id, 1, total).param("id", id));
    }
  }

  @Test
  void rejectsNumZeroOrAboveTheTotal() {
    Fixture f = fixture();
    UUID first = compra(f, 3);

    assertConstraint("lancamento_parcela_num_faixa", parcela(f, f.cartao, first, 0, 3));
    assertConstraint("lancamento_parcela_num_faixa", parcela(f, f.cartao, first, 4, 3));
  }

  @Test
  void rejectsFirstNotPointingToItselfAndOthersPointingToThemselves() {
    Fixture f = fixture();
    UUID first = compra(f, 3);
    UUID id = UUID.randomUUID();

    assertConstraint(
        "lancamento_parcela_primeira_aponta_para_si", parcela(f, f.cartao, first, 1, 3));
    assertConstraint(
        "lancamento_parcela_primeira_aponta_para_si",
        parcela(f, f.cartao, id, 2, 3).param("id", id));
  }

  @Test
  void rejectsParcelaWithTemplateOrParcelaOrdem() {
    Fixture f = fixture();
    UUID first = compra(f, 3);
    UUID template = templateOf(f);

    assertConstraint(
        "lancamento_parcela_sem_template",
        parcela(f, f.cartao, first, 2, 3)
            .param("template", template)
            .param("ocorrencia", "2026-11-05"));
    assertConstraint(
        "lancamento_parcela_sem_template",
        parcela(f, f.cartao, first, 2, 3)
            .param("template", template)
            .param("ocorrencia", "2026-11-05")
            .param("ordem", 1));
  }

  @Test
  void rejectsParcelaThatIsReceitaOrNotRealizado() {
    Fixture f = fixture();
    UUID first = compra(f, 3);

    assertConstraint(
        "lancamento_parcela_so_despesa_realizada",
        parcela(f, f.cartao, first, 2, 3)
            .param("tipo", "receita")
            .param("categoria", f.categoriaReceita));
    assertConstraint(
        "lancamento_parcela_so_despesa_realizada",
        parcela(f, f.cartao, first, 2, 3).param("status", "previsto"));
    assertConstraint(
        "lancamento_parcela_so_despesa_realizada",
        parcela(f, f.cartao, first, 2, 3).param("status", "cancelado"));
  }

  @Test
  void rejectsParcelaPointingToALancamentoOfAnotherUsuario() {
    Fixture f = fixture();
    Fixture other = fixture();
    UUID otherFirst = compra(other, 3);

    assertConstraint("lancamento_parcela_mesma_compra", parcela(f, f.cartao, otherFirst, 2, 3));
  }

  @Test
  void rejectsParcelaPointingToAMissingLancamento() {
    Fixture f = fixture();

    assertConstraint(
        "lancamento_parcela_mesma_compra", parcela(f, f.cartao, UUID.randomUUID(), 2, 3));
  }

  @Test
  void rejectsParcelaWithATotalDifferentFromTheFirst() {
    Fixture f = fixture();
    UUID first = compra(f, 3);

    assertConstraint("lancamento_parcela_mesma_compra", parcela(f, f.cartao, first, 2, 4));
  }

  @Test
  void rejectsParcelaPointingToAParcelaThatIsNotTheFirst() {
    Fixture f = fixture();
    UUID first = compra(f, 3);
    UUID second = UUID.randomUUID();
    parcela(f, f.cartao, first, 2, 3).param("id", second).update();

    assertConstraint("lancamento_parcela_mesma_compra", parcela(f, f.cartao, second, 3, 3));
  }

  @Test
  void rejectsParcelaInAContaThatIsNotCartaoCredito() {
    Fixture f = fixture();
    UUID first = compra(f, 3);
    UUID id = UUID.randomUUID();

    assertConstraint("lancamento_parcela_conta_cartao", parcela(f, f.corrente, first, 2, 3));
    assertConstraint(
        "lancamento_parcela_conta_cartao", parcela(f, f.corrente, id, 1, 3).param("id", id));
  }

  @Test
  void rejectsChangingTheTipoOfAContaThatHoldsParcelas() {
    Fixture f = fixture();
    compra(f, 3);

    assertConstraint(
        "lancamento_parcela_conta_cartao",
        jdbc.sql("UPDATE conta SET tipo = 'corrente' WHERE id = :id").param("id", f.cartao));
  }

  @Test
  void refusesToDeleteTheFirstWhileOthersPointToIt() {
    Fixture f = fixture();
    UUID first = compra(f, 3);

    assertConstraint(
        "lancamento_parcela_mesma_compra",
        jdbc.sql("DELETE FROM lancamento WHERE id = :id").param("id", first));
    assertThat(count("parcela_de_id", first)).isEqualTo(3);
  }

  @Test
  void deletesAllParcelasInOneStatementEvenThoughTheFirstPointsToItself() {
    Fixture f = fixture();
    UUID first = compra(f, 12);

    int deleted =
        jdbc.sql("DELETE FROM lancamento WHERE parcela_de_id = :id").param("id", first).update();

    assertThat(deleted).isEqualTo(12);
    assertThat(count("id", first)).isZero();
  }

  @Test
  void deletesOneNonFirstParcelaAlone() {
    Fixture f = fixture();
    UUID first = compra(f, 3);
    UUID second = UUID.randomUUID();
    parcela(f, f.cartao, first, 2, 3).param("id", second).update();

    assertThat(jdbc.sql("DELETE FROM lancamento WHERE id = :id").param("id", second).update())
        .isEqualTo(1);
  }

  @Test
  void deletesTheUsuarioWithTheWholeCompra() {
    Fixture f = fixture();
    UUID first = compra(f, 5);

    int deleted = jdbc.sql("DELETE FROM usuario WHERE id = :id").param("id", f.usuario).update();

    assertThat(deleted).isEqualTo(1);
    assertThat(count("parcela_de_id", first)).isZero();
    assertThat(count("usuario_id", f.usuario)).isZero();
  }

  private record Fixture(
      UUID usuario, UUID corrente, UUID cartao, UUID categoriaDespesa, UUID categoriaReceita) {}

  private Fixture fixture() {
    UUID usuario = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuario)
        .param("email", "parcela-" + usuario + "@example.com")
        .update();
    return new Fixture(
        usuario,
        conta(usuario, "corrente"),
        conta(usuario, "cartao_credito"),
        categoria(usuario, "despesa"),
        categoria(usuario, "receita"));
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

  private UUID categoria(UUID usuario, String tipo) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :usuario, :nome,"
                + " :tipo)")
        .param("id", id)
        .param("usuario", usuario)
        .param("nome", "Teste " + tipo)
        .param("tipo", tipo)
        .update();
    return id;
  }

  private UUID templateOf(Fixture f) {
    UUID regra = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO regra_recorrencia (id, usuario_id, frequencia, dia_do_mes, inicio)"
                + " VALUES (:id, :usuario, 'mensal', 5, DATE '2026-10-19')")
        .param("id", regra)
        .param("usuario", f.usuario)
        .update();
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO template_lancamento (id, usuario_id, tipo, titulo, categoria_id,"
                + " conta_id, valor_total, regra_id) VALUES (:id, :usuario, 'despesa', 'Aluguel',"
                + " :categoria, :conta, 100.00, :regra)")
        .param("id", id)
        .param("usuario", f.usuario)
        .param("categoria", f.categoriaDespesa)
        .param("conta", f.corrente)
        .param("regra", regra)
        .update();
    return id;
  }

  // Cria a compra inteira no cartão: a primeira aponta para si, as outras para a primeira.
  // Devolve o id da primeira.
  private UUID compra(Fixture f, int total) {
    UUID first = UUID.randomUUID();
    parcela(f, f.cartao, first, 1, total).param("id", first).update();
    for (int num = 2; num <= total; num++) {
      parcela(f, f.cartao, first, num, total).update();
    }
    return first;
  }

  // Despesa realizada de R$ 10,00 com as colunas de parcela como vierem. Os testes sobrescrevem
  // com .param o que querem quebrar (id, tipo, status, categoria, template).
  private JdbcClient.StatementSpec parcela(
      Fixture f, UUID conta, UUID de, Integer num, Integer total) {
    return jdbc.sql(
            "INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,"
                + " data_competencia, data_caixa, status, template_id, ocorrencia_data,"
                + " parcela_ordem, parcela_de_id, parcela_num, parcela_total) VALUES (:id,"
                + " :usuario, :tipo, 'Compra', :categoria, :conta, 10.00, DATE '2026-11-05',"
                + " DATE '2026-11-05', :status, :template, CAST(:ocorrencia AS date), :ordem,"
                + " :de, :num, :total)")
        .param("id", UUID.randomUUID())
        .param("usuario", f.usuario)
        .param("tipo", "despesa")
        .param("categoria", f.categoriaDespesa)
        .param("conta", conta)
        .param("status", "realizado")
        .param("template", null, Types.OTHER)
        .param("ocorrencia", null, Types.VARCHAR)
        .param("ordem", null, Types.SMALLINT)
        .param("de", de, Types.OTHER)
        .param("num", num, Types.SMALLINT)
        .param("total", total, Types.SMALLINT);
  }

  private long count(String column, UUID id) {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE " + column + " = :id")
        .param("id", id)
        .query(Long.class)
        .single();
  }

  private void assertConstraint(String constraint, JdbcClient.StatementSpec statement) {
    assertThatThrownBy(statement::update)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }
}
