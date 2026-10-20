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
class RecorrenciaMigrationTest {

  @Autowired JdbcClient jdbc;

  @Test
  void acceptsAValidRegraForEachFrequencia() {
    Fixture f = fixture();

    assertThat(regra(f, "diaria", null, null, "2026-10-19", null, null).update()).isEqualTo(1);
    assertThat(regra(f, "semanal", null, 6, "2026-10-19", null, null).update()).isEqualTo(1);
    assertThat(regra(f, "mensal", 31, null, "2026-10-19", null, null).update()).isEqualTo(1);
    assertThat(regra(f, "anual", null, null, "2026-10-19", "2030-01-01", null).update())
        .isEqualTo(1);
    assertThat(regra(f, "mensal", 5, null, "2026-10-19", null, 10000).update()).isEqualTo(1);
  }

  @Test
  void rejectsFrequenciaOutsideTheList() {
    Fixture f = fixture();

    // o Postgres reporta o primeiro CHECK que falha, em ordem alfabética, e o de campos também
    // recusa

    assertConstraint(
        "regra_campos_conforme_frequencia",
        regra(f, "quinzenal", null, null, "2026-10-19", null, null));
  }

  @Test
  void rejectsIntervaloOutsideOneToNinetyNine() {
    Fixture f = fixture();

    for (int intervalo : new int[] {0, 100}) {
      assertConstraint(
          "regra_intervalo_faixa",
          regra(f, "diaria", null, null, "2026-10-19", null, null).param("intervalo", intervalo));
    }
    assertThat(
            regra(f, "diaria", null, null, "2026-10-19", null, null)
                .param("intervalo", 99)
                .update())
        .isEqualTo(1);
  }

  @Test
  void rejectsDiaOutsideItsRange() {
    Fixture f = fixture();

    assertConstraint(
        "regra_dia_do_mes_faixa", regra(f, "mensal", 32, null, "2026-10-19", null, null));
    assertConstraint(
        "regra_dia_do_mes_faixa", regra(f, "mensal", 0, null, "2026-10-19", null, null));
    assertConstraint(
        "regra_dia_da_semana_faixa", regra(f, "semanal", null, 7, "2026-10-19", null, null));
  }

  @Test
  void bindsTheDiaFieldsToTheFrequencia() {
    Fixture f = fixture();

    assertConstraint(
        "regra_campos_conforme_frequencia",
        regra(f, "mensal", null, null, "2026-10-19", null, null));
    assertConstraint(
        "regra_campos_conforme_frequencia", regra(f, "mensal", 5, 2, "2026-10-19", null, null));
    assertConstraint(
        "regra_campos_conforme_frequencia", regra(f, "semanal", 5, null, "2026-10-19", null, null));
    assertConstraint(
        "regra_campos_conforme_frequencia",
        regra(f, "semanal", null, null, "2026-10-19", null, null));
    assertConstraint(
        "regra_campos_conforme_frequencia", regra(f, "diaria", 5, null, "2026-10-19", null, null));
    assertConstraint(
        "regra_campos_conforme_frequencia", regra(f, "anual", null, 1, "2026-10-19", null, null));
  }

  @Test
  void rejectsFimBeforeInicioAndAcceptsEqual() {
    Fixture f = fixture();

    assertConstraint(
        "regra_fim_faixa", regra(f, "diaria", null, null, "2026-10-19", "2026-10-18", null));
    assertThat(regra(f, "diaria", null, null, "2026-10-19", "2026-10-19", null).update())
        .isEqualTo(1);
  }

  @Test
  void rejectsInicioOutsideTheYearRange() {
    Fixture f = fixture();

    assertConstraint(
        "regra_inicio_faixa", regra(f, "diaria", null, null, "1899-12-31", null, null));
    assertConstraint(
        "regra_inicio_faixa", regra(f, "diaria", null, null, "2200-01-01", null, null));
  }

  @Test
  void rejectsOcorrenciasMaxOutsideItsRange() {
    Fixture f = fixture();

    assertConstraint(
        "regra_ocorrencias_max_faixa", regra(f, "diaria", null, null, "2026-10-19", null, 0));
    assertConstraint(
        "regra_ocorrencias_max_faixa", regra(f, "diaria", null, null, "2026-10-19", null, 10001));
  }

  @Test
  void rejectsFimAndOcorrenciasMaxTogether() {
    Fixture f = fixture();

    assertConstraint(
        "regra_fim_ou_ocorrencias_max",
        regra(f, "diaria", null, null, "2026-10-19", "2027-01-01", 5));
  }

  @Test
  void acceptsATemplateAndRejectsInvalidFields() {
    Fixture f = fixture();

    assertThat(template(f, f.categoriaDespesa, f.conta, regra(f)).update()).isEqualTo(1);
    assertConstraint(
        "template_valor_total_faixa",
        template(f, f.categoriaDespesa, f.conta, regra(f)).param("valor", "0"));
    assertConstraint(
        "template_valor_total_faixa",
        template(f, f.categoriaDespesa, f.conta, regra(f)).param("valor", "90071992547409.92"));
    assertConstraint(
        "template_titulo_sem_espaco_nas_pontas",
        template(f, f.categoriaDespesa, f.conta, regra(f)).param("titulo", " Aluguel"));
    assertConstraint(
        "template_titulo_tamanho",
        template(f, f.categoriaDespesa, f.conta, regra(f)).param("titulo", ""));
    assertConstraint(
        "template_descricao_tamanho",
        template(f, f.categoriaDespesa, f.conta, regra(f)).param("descricao", "x".repeat(501)));
    assertConstraint(
        "template_tipo_valido",
        template(f, f.categoriaDespesa, f.conta, regra(f)).param("tipo", "transferencia"));
  }

  @Test
  void rejectsTemplateWithResourcesOfAnotherUsuario() {
    Fixture f = fixture();
    Fixture other = fixture();

    assertConstraint(
        "template_categoria_mesmo_usuario_e_tipo",
        template(f, other.categoriaDespesa, f.conta, regra(f)));
    assertConstraint(
        "template_conta_mesmo_usuario", template(f, f.categoriaDespesa, other.conta, regra(f)));
    assertConstraint(
        "template_regra_mesmo_usuario", template(f, f.categoriaDespesa, f.conta, regra(other)));
  }

  @Test
  void rejectsTemplateWithCategoriaOfTheWrongTipo() {
    Fixture f = fixture();

    assertConstraint(
        "template_categoria_mesmo_usuario_e_tipo",
        template(f, f.categoriaReceita, f.conta, regra(f)));
  }

  @Test
  void rejectsTwoTemplatesOnTheSameRegra() {
    Fixture f = fixture();
    UUID regra = regra(f);
    template(f, f.categoriaDespesa, f.conta, regra).update();

    assertConstraint("template_regra_unica", template(f, f.categoriaDespesa, f.conta, regra));
  }

  @Test
  void rejectsLancamentoWithTemplateOfAnotherUsuario() {
    Fixture f = fixture();
    Fixture other = fixture();
    UUID templateAlheio = templateOf(other);

    assertConstraint(
        "lancamento_template_mesmo_usuario",
        ocorrencia(f, templateAlheio, "2026-11-05", "previsto"));
  }

  @Test
  void requiresTemplateAndOcorrenciaDataTogether() {
    Fixture f = fixture();
    UUID template = templateOf(f);

    assertConstraint(
        "lancamento_template_e_ocorrencia_juntos", ocorrencia(f, template, null, "previsto"));
    assertConstraint(
        "lancamento_template_e_ocorrencia_juntos", ocorrencia(f, null, "2026-11-05", "previsto"));
  }

  @Test
  void rejectsDesgarradoWithoutTemplate() {
    Fixture f = fixture();

    assertConstraint(
        "lancamento_desgarrado_so_com_template",
        ocorrencia(f, null, null, "realizado").param("desgarrado", true));
    assertThat(
            ocorrencia(f, templateOf(f), "2026-11-05", "previsto")
                .param("desgarrado", true)
                .update())
        .isEqualTo(1);
  }

  @Test
  void rejectsTheSameOcorrenciaTwiceAndOnConflictDoNothingSkipsIt() {
    Fixture f = fixture();
    UUID template = templateOf(f);
    ocorrencia(f, template, "2026-11-05", "previsto").update();

    assertConstraint(
        "lancamento_template_ocorrencia_parcela_unica",
        ocorrencia(f, template, "2026-11-05", "previsto"));
    // a desgarrada também ocupa a chave, mesmo com outra data de competência
    assertConstraint(
        "lancamento_template_ocorrencia_parcela_unica",
        ocorrencia(f, template, "2026-11-05", "realizado").param("desgarrado", true));

    int inserted =
        jdbc.sql(
                insertSql(
                    " ON CONFLICT (template_id, ocorrencia_data, parcela_ordem)"
                        + " WHERE template_id IS NOT NULL DO NOTHING"))
            .params(ocorrenciaParams(f, template, "2026-11-05", "previsto"))
            .update();
    assertThat(inserted).isZero();
    assertThat(ocorrencia(f, template, "2026-12-05", "previsto").update()).isEqualTo(1);
  }

  @Test
  void avulsoLancamentosDoNotCollideOnTheKey() {
    Fixture f = fixture();

    assertThat(ocorrencia(f, null, null, "realizado").update()).isEqualTo(1);
    assertThat(ocorrencia(f, null, null, "realizado").update()).isEqualTo(1);
  }

  @Test
  void acceptsPrevistoAndCanceladoOnReceitaAndRejectsOtherStatus() {
    Fixture f = fixture();

    assertThat(
            ocorrencia(f, null, null, "previsto")
                .param("tipo", "receita")
                .param("categoria", f.categoriaReceita)
                .update())
        .isEqualTo(1);
    assertThat(ocorrencia(f, null, null, "cancelado").update()).isEqualTo(1);
    assertConstraint("lancamento_status_valido", ocorrencia(f, null, null, "pago"));
  }

  @Test
  void keepsTransferenciaRealizadoAndWithoutTemplate() {
    Fixture f = fixture();

    assertConstraint(
        "lancamento_transferencia_realizada_sem_template",
        ocorrencia(f, null, null, "previsto")
            .param("tipo", "transferencia")
            .param("categoria", null, java.sql.Types.OTHER));
    assertConstraint(
        "lancamento_transferencia_realizada_sem_template",
        ocorrencia(f, null, null, "cancelado")
            .param("tipo", "transferencia")
            .param("categoria", null, java.sql.Types.OTHER));
    assertConstraint(
        "lancamento_transferencia_realizada_sem_template",
        ocorrencia(f, templateOf(f), "2026-11-05", "realizado")
            .param("tipo", "transferencia")
            .param("categoria", null, java.sql.Types.OTHER));
  }

  @Test
  void refusesToDeleteATemplateWithOcorrencias() {
    Fixture f = fixture();
    UUID template = templateOf(f);
    ocorrencia(f, template, "2026-11-05", "previsto").update();

    assertConstraint(
        "lancamento_template_mesmo_usuario",
        jdbc.sql("DELETE FROM template_lancamento WHERE id = :id").param("id", template));
  }

  @Test
  void deletesEverythingWhenTheUsuarioIsDeleted() {
    Fixture f = fixture();
    UUID template = templateOf(f);
    ocorrencia(f, template, "2026-11-05", "previsto").update();

    int deleted = jdbc.sql("DELETE FROM usuario WHERE id = :id").param("id", f.usuario).update();

    assertThat(deleted).isEqualTo(1);
    assertThat(count("lancamento", f.usuario)).isZero();
    assertThat(count("template_lancamento", f.usuario)).isZero();
    assertThat(count("regra_recorrencia", f.usuario)).isZero();
    assertThat(count("conta", f.usuario)).isZero();
  }

  private record Fixture(UUID usuario, UUID conta, UUID categoriaDespesa, UUID categoriaReceita) {}

  private Fixture fixture() {
    UUID usuario = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuario)
        .param("email", "rec-" + usuario + "@example.com")
        .update();
    UUID conta = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuario, 'Conta',"
                + " 'dinheiro')")
        .param("id", conta)
        .param("usuario", usuario)
        .update();
    return new Fixture(
        usuario,
        conta,
        categoria(usuario, "Teste despesa", "despesa"),
        categoria(usuario, "Teste receita", "receita"));
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

  private JdbcClient.StatementSpec regra(
      Fixture f,
      String frequencia,
      Integer diaDoMes,
      Integer diaDaSemana,
      String inicio,
      String fim,
      Integer ocorrenciasMax) {
    return jdbc.sql(
            "INSERT INTO regra_recorrencia (id, usuario_id, frequencia, intervalo, dia_do_mes,"
                + " dia_da_semana, inicio, fim, ocorrencias_max) VALUES (:id, :usuario,"
                + " :frequencia, :intervalo, :diaDoMes, :diaDaSemana, CAST(:inicio AS date),"
                + " CAST(:fim AS date), :ocorrenciasMax)")
        .param("id", UUID.randomUUID())
        .param("usuario", f.usuario)
        .param("frequencia", frequencia)
        .param("intervalo", 1)
        .param("diaDoMes", diaDoMes, java.sql.Types.SMALLINT)
        .param("diaDaSemana", diaDaSemana, java.sql.Types.SMALLINT)
        .param("inicio", inicio)
        .param("fim", fim, java.sql.Types.VARCHAR)
        .param("ocorrenciasMax", ocorrenciasMax, java.sql.Types.INTEGER);
  }

  // regra mensal válida, devolve o id para o template apontar
  private UUID regra(Fixture f) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO regra_recorrencia (id, usuario_id, frequencia, dia_do_mes, inicio)"
                + " VALUES (:id, :usuario, 'mensal', 5, DATE '2026-10-19')")
        .param("id", id)
        .param("usuario", f.usuario)
        .update();
    return id;
  }

  private JdbcClient.StatementSpec template(Fixture f, UUID categoria, UUID conta, UUID regra) {
    return jdbc.sql(
            "INSERT INTO template_lancamento (id, usuario_id, tipo, titulo, descricao,"
                + " categoria_id, conta_id, valor_total, regra_id) VALUES (:id, :usuario, :tipo,"
                + " :titulo, :descricao, :categoria, :conta, CAST(:valor AS numeric), :regra)")
        .param("id", UUID.randomUUID())
        .param("usuario", f.usuario)
        .param("tipo", "despesa")
        .param("titulo", "Aluguel")
        .param("descricao", null, java.sql.Types.VARCHAR)
        .param("categoria", categoria)
        .param("conta", conta)
        .param("valor", "100.00")
        .param("regra", regra);
  }

  private UUID templateOf(Fixture f) {
    UUID id = UUID.randomUUID();
    template(f, f.categoriaDespesa, f.conta, regra(f)).param("id", id).update();
    return id;
  }

  private static final String INSERT =
      "INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,"
          + " data_competencia, data_caixa, status, template_id, ocorrencia_data, desgarrado)"
          + " VALUES (:id, :usuario, :tipo, 'Aluguel', :categoria, :conta, 10.00,"
          + " DATE '2026-11-05', DATE '2026-11-05', :status, :template,"
          + " CAST(:ocorrenciaData AS date), :desgarrado)";

  private String insertSql(String suffix) {
    return INSERT + suffix;
  }

  private java.util.Map<String, Object> ocorrenciaParams(
      Fixture f, UUID template, String ocorrenciaData, String status) {
    java.util.Map<String, Object> params = new java.util.HashMap<>();
    params.put("id", UUID.randomUUID());
    params.put("usuario", f.usuario);
    params.put("tipo", "despesa");
    params.put("categoria", f.categoriaDespesa);
    params.put("conta", f.conta);
    params.put("status", status);
    params.put("template", template);
    params.put("ocorrenciaData", ocorrenciaData);
    params.put("desgarrado", false);
    return params;
  }

  private JdbcClient.StatementSpec ocorrencia(
      Fixture f, UUID template, String ocorrenciaData, String status) {
    return jdbc.sql(INSERT)
        .param("id", UUID.randomUUID())
        .param("usuario", f.usuario)
        .param("tipo", "despesa")
        .param("categoria", f.categoriaDespesa)
        .param("conta", f.conta)
        .param("status", status)
        .param("template", template, java.sql.Types.OTHER)
        .param("ocorrenciaData", ocorrenciaData, java.sql.Types.VARCHAR)
        .param("desgarrado", false);
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
