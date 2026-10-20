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
class DivisaoMigrationTest {

  @Autowired JdbcClient jdbc;

  @Test
  void acceptsPercentualAndValorFixoParcelas() {
    Fixture f = fixture();
    UUID template = templateOf(f);

    assertThat(divisao(f, template, 15, "40.00", null, 1).update()).isEqualTo(1);
    assertThat(divisao(f, template, 30, "99.99", null, 2).update()).isEqualTo(1);
    assertThat(divisao(f, templateOf(f), 1, null, "0.01", 10).update()).isEqualTo(1);
    assertThat(divisao(f, templateOf(f), 31, null, "90071992547409.91", 1).update()).isEqualTo(1);
  }

  @Test
  void requiresExactlyOneOfPercentualAndValorFixo() {
    Fixture f = fixture();
    UUID template = templateOf(f);

    assertConstraint("divisao_percentual_ou_valor", divisao(f, template, 15, "40.00", "10.00", 1));
    assertConstraint("divisao_percentual_ou_valor", divisao(f, template, 15, null, null, 1));
  }

  @Test
  void rejectsPercentualOutsideTheOpenRangeZeroToOneHundred() {
    Fixture f = fixture();
    UUID template = templateOf(f);

    for (String percentual : new String[] {"0", "0.00", "100", "100.00", "-1"}) {
      assertConstraint("divisao_percentual_faixa", divisao(f, template, 15, percentual, null, 1));
    }
  }

  @Test
  void rejectsValorFixoOutsideItsRange() {
    Fixture f = fixture();
    UUID template = templateOf(f);

    assertConstraint("divisao_valor_fixo_faixa", divisao(f, template, 15, null, "0", 1));
    assertConstraint(
        "divisao_valor_fixo_faixa", divisao(f, template, 15, null, "90071992547409.92", 1));
  }

  @Test
  void rejectsOrdemOutsideOneToTen() {
    Fixture f = fixture();
    UUID template = templateOf(f);

    assertConstraint("divisao_ordem_faixa", divisao(f, template, 15, "40.00", null, 0));
    assertConstraint("divisao_ordem_faixa", divisao(f, template, 15, "40.00", null, 11));
  }

  @Test
  void rejectsDiaDoMesOutsideOneToThirtyOne() {
    Fixture f = fixture();
    UUID template = templateOf(f);

    assertConstraint("divisao_dia_do_mes_faixa", divisao(f, template, 0, "40.00", null, 1));
    assertConstraint("divisao_dia_do_mes_faixa", divisao(f, template, 32, "40.00", null, 1));
  }

  @Test
  void rejectsRepeatedDiaAndRepeatedOrdemInTheSameTemplate() {
    Fixture f = fixture();
    UUID template = templateOf(f);
    divisao(f, template, 15, "40.00", null, 1).update();

    assertConstraint("divisao_template_dia_unico", divisao(f, template, 15, "60.00", null, 2));
    assertConstraint("divisao_template_ordem_unica", divisao(f, template, 30, "60.00", null, 1));
    // outro template pode repetir dia e ordem
    assertThat(divisao(f, templateOf(f), 15, "40.00", null, 1).update()).isEqualTo(1);
  }

  @Test
  void rejectsParcelaOfATemplateOfAnotherUsuario() {
    Fixture f = fixture();
    Fixture other = fixture();

    assertConstraint(
        "divisao_template_mesmo_usuario", divisao(f, templateOf(other), 15, "40.00", null, 1));
  }

  @Test
  void refusesToDeleteATemplateWithParcelas() {
    Fixture f = fixture();
    UUID template = templateOf(f);
    divisao(f, template, 15, "40.00", null, 1).update();

    assertConstraint(
        "divisao_template_mesmo_usuario",
        jdbc.sql("DELETE FROM template_lancamento WHERE id = :id").param("id", template));
  }

  @Test
  void rejectsParcelaOrdemWithoutTemplateOrOutsideItsRange() {
    Fixture f = fixture();
    UUID template = templateOf(f);

    assertConstraint(
        "lancamento_parcela_so_com_template", ocorrencia(f, null, null, "previsto", 1));
    assertConstraint(
        "lancamento_parcela_ordem_faixa", ocorrencia(f, template, "2026-11-05", "previsto", 0));
    assertConstraint(
        "lancamento_parcela_ordem_faixa", ocorrencia(f, template, "2026-11-05", "previsto", 11));
  }

  @Test
  void acceptsTwoParcelasOnTheSameDateAndRejectsTheSameParcelaTwice() {
    Fixture f = fixture();
    UUID template = templateOf(f);

    assertThat(ocorrencia(f, template, "2027-02-28", "previsto", 1).update()).isEqualTo(1);
    assertThat(ocorrencia(f, template, "2027-02-28", "previsto", 2).update()).isEqualTo(1);

    assertConstraint(
        "lancamento_template_ocorrencia_parcela_unica",
        ocorrencia(f, template, "2027-02-28", "previsto", 2));
  }

  @Test
  void keepsTheOldKeyForTemplateWithoutParcela() {
    Fixture f = fixture();
    UUID template = templateOf(f);
    ocorrencia(f, template, "2026-11-05", "previsto", null).update();

    // NULLS NOT DISTINCT: dois sem parcela na mesma data continuam colidindo
    assertConstraint(
        "lancamento_template_ocorrencia_parcela_unica",
        ocorrencia(f, template, "2026-11-05", "previsto", null));
    // e a ocorrência sem parcela não colide com a parcelada da mesma data
    assertThat(ocorrencia(f, template, "2026-11-05", "previsto", 1).update()).isEqualTo(1);
  }

  @Test
  void onConflictWithTheThreeColumnsSkipsDuplicatesWithAndWithoutParcela() {
    Fixture f = fixture();
    UUID template = templateOf(f);
    String onConflict =
        " ON CONFLICT (template_id, ocorrencia_data, parcela_ordem)"
            + " WHERE template_id IS NOT NULL DO NOTHING";

    assertThat(insertOnConflict(f, template, "2026-11-05", null, onConflict)).isEqualTo(1);
    assertThat(insertOnConflict(f, template, "2026-11-05", null, onConflict)).isZero();
    assertThat(insertOnConflict(f, template, "2026-11-05", 1, onConflict)).isEqualTo(1);
    assertThat(insertOnConflict(f, template, "2026-11-05", 1, onConflict)).isZero();
    assertThat(insertOnConflict(f, template, "2026-11-05", 2, onConflict)).isEqualTo(1);
  }

  @Test
  void deletesEverythingWhenTheUsuarioIsDeleted() {
    Fixture f = fixture();
    UUID template = templateOf(f);
    divisao(f, template, 15, "40.00", null, 1).update();
    divisao(f, template, 30, "60.00", null, 2).update();
    ocorrencia(f, template, "2026-11-15", "previsto", 1).update();
    ocorrencia(f, template, "2026-11-30", "previsto", 2).update();

    int deleted = jdbc.sql("DELETE FROM usuario WHERE id = :id").param("id", f.usuario).update();

    assertThat(deleted).isEqualTo(1);
    assertThat(count("template_divisao", f.usuario)).isZero();
    assertThat(count("lancamento", f.usuario)).isZero();
    assertThat(count("template_lancamento", f.usuario)).isZero();
  }

  private record Fixture(UUID usuario, UUID conta, UUID categoria) {}

  private Fixture fixture() {
    UUID usuario = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuario)
        .param("email", "div-" + usuario + "@example.com")
        .update();
    UUID conta = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuario, 'Conta',"
                + " 'dinheiro')")
        .param("id", conta)
        .param("usuario", usuario)
        .update();
    UUID categoria = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo) VALUES (:id, :usuario, 'Teste',"
                + " 'receita')")
        .param("id", categoria)
        .param("usuario", usuario)
        .update();
    return new Fixture(usuario, conta, categoria);
  }

  private UUID templateOf(Fixture f) {
    UUID regra = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO regra_recorrencia (id, usuario_id, frequencia, dia_do_mes, inicio)"
                + " VALUES (:id, :usuario, 'mensal', 15, DATE '2026-10-19')")
        .param("id", regra)
        .param("usuario", f.usuario)
        .update();
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO template_lancamento (id, usuario_id, tipo, titulo, categoria_id,"
                + " conta_id, valor_total, regra_id) VALUES (:id, :usuario, 'receita', 'Salário',"
                + " :categoria, :conta, 1000.00, :regra)")
        .param("id", id)
        .param("usuario", f.usuario)
        .param("categoria", f.categoria)
        .param("conta", f.conta)
        .param("regra", regra)
        .update();
    return id;
  }

  private JdbcClient.StatementSpec divisao(
      Fixture f, UUID template, int dia, String percentual, String valorFixo, int ordem) {
    return jdbc.sql(
            "INSERT INTO template_divisao (id, usuario_id, template_id, dia_do_mes, percentual,"
                + " valor_fixo, ordem) VALUES (:id, :usuario, :template, :dia,"
                + " CAST(:percentual AS numeric), CAST(:valorFixo AS numeric), :ordem)")
        .param("id", UUID.randomUUID())
        .param("usuario", f.usuario)
        .param("template", template)
        .param("dia", dia)
        .param("percentual", percentual, java.sql.Types.VARCHAR)
        .param("valorFixo", valorFixo, java.sql.Types.VARCHAR)
        .param("ordem", ordem);
  }

  private static final String INSERT =
      "INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,"
          + " data_competencia, data_caixa, status, template_id, ocorrencia_data, parcela_ordem)"
          + " VALUES (:id, :usuario, 'receita', 'Salário', :categoria, :conta, 10.00,"
          + " DATE '2026-11-05', DATE '2026-11-05', :status, :template,"
          + " CAST(:ocorrenciaData AS date), :parcelaOrdem)";

  private JdbcClient.StatementSpec ocorrencia(
      Fixture f, UUID template, String ocorrenciaData, String status, Integer parcelaOrdem) {
    return jdbc.sql(INSERT)
        .param("id", UUID.randomUUID())
        .param("usuario", f.usuario)
        .param("categoria", f.categoria)
        .param("conta", f.conta)
        .param("status", status)
        .param("template", template, java.sql.Types.OTHER)
        .param("ocorrenciaData", ocorrenciaData, java.sql.Types.VARCHAR)
        .param("parcelaOrdem", parcelaOrdem, java.sql.Types.SMALLINT);
  }

  private int insertOnConflict(
      Fixture f, UUID template, String ocorrenciaData, Integer parcelaOrdem, String suffix) {
    return jdbc.sql(INSERT + suffix)
        .param("id", UUID.randomUUID())
        .param("usuario", f.usuario)
        .param("categoria", f.categoria)
        .param("conta", f.conta)
        .param("status", "previsto")
        .param("template", template)
        .param("ocorrenciaData", ocorrenciaData)
        .param("parcelaOrdem", parcelaOrdem, java.sql.Types.SMALLINT)
        .update();
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
