package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CategoriaLancamentoMigrationTest {

  private static final List<String> SEED_NAMES =
      List.of(
          "Salário",
          "Aluguel recebido",
          "Benefício",
          "Rendimento",
          "Outras receitas",
          "Alimentação",
          "Refeição",
          "Mercado",
          "Combustível",
          "Transporte",
          "Moradia",
          "Saúde",
          "Lazer",
          "Assinatura",
          "Educação",
          "Outros");

  @Autowired JdbcClient jdbc;

  @Test
  void seedsExistingUsuariosWhenMigratingFromV5ToV6() {
    try (PostgreSQLContainer own = TestcontainersConfiguration.newPostgres()) {
      own.start();
      Flyway before = flyway(own, "5");
      before.migrate();
      JdbcClient owner = ownerClient(own);
      UUID usuarioId = UUID.randomUUID();
      owner
          .sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
          .param("id", usuarioId)
          .param("email", "antigo@example.com")
          .update();

      flyway(own, "6").migrate();

      List<String> nomes =
          owner
              .sql("SELECT nome FROM categoria WHERE usuario_id = :id ORDER BY nome")
              .param("id", usuarioId)
              .query(String.class)
              .list();
      assertThat(nomes).hasSize(16).containsExactlyInAnyOrderElementsOf(SEED_NAMES);
      assertThat(tipoDe(owner, usuarioId, "Outros")).isEqualTo("despesa");
      assertThat(tipoDe(owner, usuarioId, "Outras receitas")).isEqualTo("receita");
      assertThat(tipoDe(owner, usuarioId, "Salário")).isEqualTo("receita");
    }
  }

  @Test
  void rejectsTipoOutsideTheAllowedList() {
    Fixture f = fixture();

    assertConstraint(
        "lancamento_tipo_valido",
        insertLancamento(f, "transferencia", f.categoriaDespesa, f.conta));
  }

  @Test
  void rejectsStatusOtherThanRealizado() {
    Fixture f = fixture();

    assertConstraint(
        "lancamento_status_valido",
        lancamento(f, "despesa", "Mercado", null, f.categoriaDespesa, f.conta, "10", "2026-10-19")
            .param("status", "previsto"));
  }

  @Test
  void rejectsValorZeroNegativeAndAboveTheCeiling() {
    Fixture f = fixture();

    for (String valor : List.of("0", "-0.01", "90071992547409.92")) {
      assertConstraint(
          "lancamento_valor_faixa",
          lancamento(
              f, "despesa", "Mercado", null, f.categoriaDespesa, f.conta, valor, "2026-10-19"));
    }
  }

  @Test
  void acceptsValorAtTheLimits() {
    Fixture f = fixture();

    for (String valor : List.of("0.01", "90071992547409.91")) {
      assertThat(
              lancamento(
                      f,
                      "despesa",
                      "Mercado",
                      null,
                      f.categoriaDespesa,
                      f.conta,
                      valor,
                      "2026-10-19")
                  .update())
          .isEqualTo(1);
    }
  }

  @Test
  void rejectsDatesOutsideYears1900To2199() {
    Fixture f = fixture();

    assertConstraint(
        "lancamento_data_competencia_faixa", lancamentoComDatas(f, "1899-12-31", "2026-10-19"));
    assertConstraint(
        "lancamento_data_caixa_faixa", lancamentoComDatas(f, "2026-10-19", "2200-01-01"));
    assertThat(lancamentoComDatas(f, "1900-01-01", "2199-12-31").update()).isEqualTo(1);
  }

  @Test
  void rejectsInvalidTitulo() {
    Fixture f = fixture();

    assertConstraint(
        "lancamento_titulo_tamanho",
        lancamento(f, "despesa", "", null, f.categoriaDespesa, f.conta, "1", "2026-10-19"));
    assertConstraint(
        "lancamento_titulo_tamanho",
        lancamento(
            f, "despesa", "a".repeat(101), null, f.categoriaDespesa, f.conta, "1", "2026-10-19"));
    assertConstraint(
        "lancamento_titulo_sem_espaco_nas_pontas",
        lancamento(f, "despesa", " Mercado", null, f.categoriaDespesa, f.conta, "1", "2026-10-19"));
    assertConstraint(
        "lancamento_titulo_sem_espaco_nas_pontas",
        lancamento(f, "despesa", "Mercado ", null, f.categoriaDespesa, f.conta, "1", "2026-10-19"));
    assertThat(
            lancamento(
                    f,
                    "despesa",
                    "a".repeat(100),
                    null,
                    f.categoriaDespesa,
                    f.conta,
                    "1",
                    "2026-10-19")
                .update())
        .isEqualTo(1);
  }

  @Test
  void limitsDescricaoTo500CodePoints() {
    Fixture f = fixture();

    assertConstraint(
        "lancamento_descricao_tamanho",
        lancamento(
            f,
            "despesa",
            "Mercado",
            "a".repeat(501),
            f.categoriaDespesa,
            f.conta,
            "1",
            "2026-10-19"));
    // 500 pontos de código fora do BMP: conta em caracteres, não em bytes nem em UTF-16
    assertThat(
            lancamento(
                    f,
                    "despesa",
                    "Mercado",
                    "😀".repeat(500),
                    f.categoriaDespesa,
                    f.conta,
                    "1",
                    "2026-10-19")
                .update())
        .isEqualTo(1);
  }

  @Test
  void rejectsLancamentoWithContaOfAnotherUsuario() {
    Fixture f = fixture();
    Fixture other = fixture();

    assertConstraint(
        "lancamento_conta_mesmo_usuario",
        insertLancamento(f, "despesa", f.categoriaDespesa, other.conta));
  }

  @Test
  void rejectsLancamentoWithCategoriaOfAnotherUsuario() {
    Fixture f = fixture();
    Fixture other = fixture();

    assertConstraint(
        "lancamento_categoria_mesmo_usuario_e_tipo",
        insertLancamento(f, "despesa", other.categoriaDespesa, f.conta));
  }

  @Test
  void rejectsDespesaWithCategoriaOfReceita() {
    Fixture f = fixture();

    assertConstraint(
        "lancamento_categoria_mesmo_usuario_e_tipo",
        insertLancamento(f, "despesa", f.categoriaReceita, f.conta));
    assertThat(insertLancamento(f, "receita", f.categoriaReceita, f.conta).update()).isEqualTo(1);
  }

  @Test
  void refusesToDeleteContaOrCategoriaThatHasLancamento() {
    Fixture f = fixture();
    insertLancamento(f, "despesa", f.categoriaDespesa, f.conta).update();

    assertConstraint(
        "lancamento_conta_mesmo_usuario",
        jdbc.sql("DELETE FROM conta WHERE id = :id").param("id", f.conta));
    assertConstraint(
        "lancamento_categoria_mesmo_usuario_e_tipo",
        jdbc.sql("DELETE FROM categoria WHERE id = :id").param("id", f.categoriaDespesa));
  }

  @Test
  void deletesEverythingWhenTheUsuarioIsDeleted() {
    Fixture f = fixture();
    insertLancamento(f, "despesa", f.categoriaDespesa, f.conta).update();

    int deleted = jdbc.sql("DELETE FROM usuario WHERE id = :id").param("id", f.usuario).update();

    assertThat(deleted).isEqualTo(1);
    assertThat(count("lancamento", f.usuario)).isZero();
    assertThat(count("conta", f.usuario)).isZero();
    assertThat(count("categoria", f.usuario)).isZero();
  }

  @Test
  void rejectsSameCategoriaNomeWithDifferentCaseUnderTheSameParent() {
    Fixture f = fixture();

    // dois nomes na raiz, onde pai_id é nulo
    insertCategoria(f.usuario, "Alimentacao", "despesa", null).update();
    assertConstraint(
        "categoria_usuario_pai_nome_unico",
        insertCategoria(f.usuario, "alimentacao", "despesa", null));
    assertConstraint(
        "categoria_usuario_pai_nome_unico",
        insertCategoria(f.usuario, "ALIMENTACAO", "despesa", null));

    // e sob o mesmo pai
    UUID pai = UUID.randomUUID();
    insertCategoria(f.usuario, "Pets", "despesa", null, pai).update();
    insertCategoria(f.usuario, "Ração", "despesa", pai).update();
    assertConstraint(
        "categoria_usuario_pai_nome_unico", insertCategoria(f.usuario, "RAÇÃO", "despesa", pai));
  }

  @Test
  void acceptsSameCategoriaNomeUnderDifferentParentsAndUsuarios() {
    Fixture f = fixture();
    UUID paiA = UUID.randomUUID();
    UUID paiB = UUID.randomUUID();
    insertCategoria(f.usuario, "Pets", "despesa", null, paiA).update();
    insertCategoria(f.usuario, "Casa", "despesa", null, paiB).update();

    assertThat(insertCategoria(f.usuario, "Outros gastos", "despesa", paiA).update()).isEqualTo(1);
    assertThat(insertCategoria(f.usuario, "Outros gastos", "despesa", paiB).update()).isEqualTo(1);
    assertThat(insertCategoria(fixture().usuario, "Pets", "despesa", null).update()).isEqualTo(1);
  }

  @Test
  void rejectsParentOfAnotherUsuario() {
    Fixture f = fixture();
    Fixture other = fixture();

    assertConstraint(
        "categoria_pai_mesmo_usuario_e_raiz",
        insertCategoria(f.usuario, "Filha", "despesa", other.categoriaDespesa));
  }

  @Test
  void rejectsMoreThanOneLevelOfHierarchy() {
    Fixture f = fixture();
    UUID pai = UUID.randomUUID();
    UUID filha = UUID.randomUUID();
    insertCategoria(f.usuario, "Pets", "despesa", null, pai).update();
    insertCategoria(f.usuario, "Ração", "despesa", pai, filha).update();

    // neta
    assertConstraint(
        "categoria_pai_mesmo_usuario_e_raiz",
        insertCategoria(f.usuario, "Premium", "despesa", filha));
    // raiz com filhas não vira filha de outra
    assertConstraint(
        "categoria_pai_mesmo_usuario_e_raiz",
        jdbc.sql("UPDATE categoria SET pai_id = :novoPai WHERE id = :id")
            .param("novoPai", f.categoriaDespesa)
            .param("id", pai));
  }

  @Test
  void rejectsInvalidCategoriaFields() {
    Fixture f = fixture();

    assertConstraint(
        "categoria_tipo_valido", insertCategoria(f.usuario, "Nova", "transferencia", null));
    assertConstraint("categoria_nome_tamanho", insertCategoria(f.usuario, "", "despesa", null));
    assertConstraint(
        "categoria_nome_tamanho", insertCategoria(f.usuario, "a".repeat(101), "despesa", null));
    assertConstraint(
        "categoria_nome_sem_espaco_nas_pontas",
        insertCategoria(f.usuario, " Nova", "despesa", null));
    assertConstraint(
        "categoria_cor_tamanho",
        insertCategoria(f.usuario, "Nova", "despesa", null).param("cor", "#FFFFFFFFFF"));
    assertConstraint(
        "categoria_icone_tamanho",
        insertCategoria(f.usuario, "Nova", "despesa", null).param("icone", "a".repeat(51)));
  }

  private record Fixture(UUID usuario, UUID conta, UUID categoriaDespesa, UUID categoriaReceita) {}

  // Nomes fora da lista das sementes, que o usuário já ganha ao ser criado pela aplicação.
  // Aqui o usuário entra por SQL cru e recebe só as categorias que o teste cria.
  private Fixture fixture() {
    UUID usuario = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuario)
        .param("email", "lanc-" + usuario + "@example.com")
        .update();
    UUID conta = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuario, 'Carteira', 'dinheiro')")
        .param("id", conta)
        .param("usuario", usuario)
        .update();
    UUID despesa = UUID.randomUUID();
    UUID receita = UUID.randomUUID();
    insertCategoria(usuario, "Teste despesa", "despesa", null, despesa).update();
    insertCategoria(usuario, "Teste receita", "receita", null, receita).update();
    return new Fixture(usuario, conta, despesa, receita);
  }

  private JdbcClient.StatementSpec insertCategoria(
      UUID usuario, String nome, String tipo, UUID pai) {
    return insertCategoria(usuario, nome, tipo, pai, UUID.randomUUID());
  }

  private JdbcClient.StatementSpec insertCategoria(
      UUID usuario, String nome, String tipo, UUID pai, UUID id) {
    return jdbc.sql(
            "INSERT INTO categoria (id, usuario_id, nome, tipo, cor, icone, pai_id)"
                + " VALUES (:id, :usuario, :nome, :tipo, :cor, :icone, :pai)")
        .param("id", id)
        .param("usuario", usuario)
        .param("nome", nome)
        .param("tipo", tipo)
        .param("cor", null, java.sql.Types.VARCHAR)
        .param("icone", null, java.sql.Types.VARCHAR)
        .param("pai", pai, java.sql.Types.OTHER);
  }

  private JdbcClient.StatementSpec insertLancamento(
      Fixture f, String tipo, UUID categoria, UUID conta) {
    return lancamento(f, tipo, "Mercado", null, categoria, conta, "10.00", "2026-10-19");
  }

  private JdbcClient.StatementSpec lancamentoComDatas(Fixture f, String competencia, String caixa) {
    return lancamentoSql(
        f, "despesa", "Mercado", null, f.categoriaDespesa, f.conta, "10.00", competencia, caixa);
  }

  private JdbcClient.StatementSpec lancamento(
      Fixture f,
      String tipo,
      String titulo,
      String descricao,
      UUID categoria,
      UUID conta,
      String valor,
      String data) {
    return lancamentoSql(f, tipo, titulo, descricao, categoria, conta, valor, data, data);
  }

  private JdbcClient.StatementSpec lancamentoSql(
      Fixture f,
      String tipo,
      String titulo,
      String descricao,
      UUID categoria,
      UUID conta,
      String valor,
      String competencia,
      String caixa) {
    return jdbc.sql(
            "INSERT INTO lancamento (id, usuario_id, tipo, titulo, descricao, categoria_id,"
                + " conta_id, valor, data_competencia, data_caixa, status)"
                + " VALUES (:id, :usuario, :tipo, :titulo, :descricao, :categoria, :conta,"
                + " CAST(:valor AS numeric), CAST(:competencia AS date), CAST(:caixa AS date),"
                + " COALESCE(CAST(:status AS text), 'realizado'))")
        .param("id", UUID.randomUUID())
        .param("usuario", f.usuario)
        .param("tipo", tipo)
        .param("titulo", titulo)
        .param("descricao", descricao, java.sql.Types.VARCHAR)
        .param("categoria", categoria)
        .param("conta", conta)
        .param("valor", valor)
        .param("competencia", competencia)
        .param("caixa", caixa)
        .param("status", null, java.sql.Types.VARCHAR);
  }

  private long count(String table, UUID usuario) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE usuario_id = :id")
        .param("id", usuario)
        .query(Long.class)
        .single();
  }

  private static String tipoDe(JdbcClient owner, UUID usuarioId, String nome) {
    return owner
        .sql("SELECT tipo FROM categoria WHERE usuario_id = :id AND nome = :nome")
        .param("id", usuarioId)
        .param("nome", nome)
        .query(String.class)
        .single();
  }

  private static Flyway flyway(PostgreSQLContainer container, String target) {
    return Flyway.configure()
        .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
        .locations("classpath:db/migration")
        .placeholders(java.util.Map.of("app_user", TestcontainersConfiguration.APP_USER))
        .target(target)
        .load();
  }

  private static JdbcClient ownerClient(PostgreSQLContainer container) {
    return JdbcClient.create(
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword()));
  }

  private void assertConstraint(String constraint, JdbcClient.StatementSpec statement) {
    assertThatThrownBy(statement::update)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }
}
