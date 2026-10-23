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
class CriptoMigrationTest {

  private static final String EVM_CONTRACT = "0x" + "ab".repeat(20);
  private static final String SOLANA_CONTRACT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

  @Autowired JdbcClient jdbc;

  @Test
  void acceptsContaCriptoWithZeroSaldo() {
    UUID usuario = usuario();

    assertThat(contaSpec(usuario, "cripto", "0").update()).isEqualTo(1);
  }

  @Test
  void rejectsContaCriptoWithSaldoInicial() {
    UUID usuario = usuario();

    assertConstraint("conta_cripto_sem_saldo", contaSpec(usuario, "cripto", "10.00"));
    assertConstraint("conta_cripto_sem_saldo", contaSpec(usuario, "cripto", "-1.00"));
  }

  @Test
  void keepsSaldoInicialFreeInOtherTipos() {
    assertThat(contaSpec(usuario(), "corrente", "10.00").update()).isEqualTo(1);
  }

  @Test
  void rejectsLancamentoInContaCripto() {
    UUID usuario = usuario();
    UUID cripto = conta(usuario, "cripto");
    UUID corrente = conta(usuario, "corrente");

    assertConstraint("lancamento_conta_aceita_lancamento", lancamento(usuario, cripto));
    assertThat(lancamento(usuario, corrente).update()).isEqualTo(1);
  }

  @Test
  void rejectsTurningAContaWithLancamentoIntoCripto() {
    UUID usuario = usuario();
    UUID corrente = conta(usuario, "corrente");
    lancamento(usuario, corrente).update();

    assertConstraint(
        "lancamento_conta_aceita_lancamento",
        jdbc.sql("UPDATE conta SET tipo = 'cripto' WHERE id = :id").param("id", corrente));
  }

  @Test
  void acceptsEachRedeWithItsContrato() {
    UUID usuario = usuario();

    assertThat(ativo(usuario, "bitcoin", null).update()).isEqualTo(1);
    assertThat(ativo(usuario, "ethereum", null).update()).isEqualTo(1);
    for (String rede : new String[] {"ethereum", "base", "arbitrum", "polygon"}) {
      assertThat(ativo(usuario, rede, EVM_CONTRACT).update()).isEqualTo(1);
    }
    assertThat(ativo(usuario, "solana", SOLANA_CONTRACT).update()).isEqualTo(1);
  }

  @Test
  void rejectsUnknownRede() {
    assertConstraint("ativo_cripto_rede_valida", ativo(usuario(), "dogechain", null));
  }

  @Test
  void rejectsContratoInBitcoin() {
    assertConstraint("ativo_cripto_contrato_formato", ativo(usuario(), "bitcoin", EVM_CONTRACT));
  }

  @Test
  void rejectsMalformedEvmContrato() {
    UUID usuario = usuario();

    assertConstraint(
        "ativo_cripto_contrato_formato", ativo(usuario, "ethereum", EVM_CONTRACT.toUpperCase()));
    assertConstraint(
        "ativo_cripto_contrato_formato", ativo(usuario, "ethereum", "0xAB" + "ab".repeat(19)));
    assertConstraint(
        "ativo_cripto_contrato_formato", ativo(usuario, "base", EVM_CONTRACT.substring(1)));
    assertConstraint("ativo_cripto_contrato_formato", ativo(usuario, "base", EVM_CONTRACT + "a"));
    assertConstraint(
        "ativo_cripto_contrato_formato", ativo(usuario, "polygon", "0x" + "g".repeat(40)));
    assertConstraint("ativo_cripto_contrato_formato", ativo(usuario, "arbitrum", SOLANA_CONTRACT));
  }

  @Test
  void rejectsMalformedSolanaContrato() {
    UUID usuario = usuario();

    // 0, O, I e l ficam fora do alfabeto base58
    assertConstraint(
        "ativo_cripto_contrato_formato",
        ativo(usuario, "solana", "0" + SOLANA_CONTRACT.substring(1)));
    assertConstraint(
        "ativo_cripto_contrato_formato",
        ativo(usuario, "solana", "O" + SOLANA_CONTRACT.substring(1)));
    assertConstraint(
        "ativo_cripto_contrato_formato",
        ativo(usuario, "solana", "l" + SOLANA_CONTRACT.substring(1)));
    assertConstraint(
        "ativo_cripto_contrato_formato",
        ativo(usuario, "solana", SOLANA_CONTRACT.substring(0, 31)));
    assertConstraint(
        "ativo_cripto_contrato_formato", ativo(usuario, "solana", SOLANA_CONTRACT + "abcdefg"));
    assertConstraint("ativo_cripto_contrato_formato", ativo(usuario, "solana", EVM_CONTRACT));
  }

  @Test
  void rejectsInvalidSimboloNomeAndDecimais() {
    UUID usuario = usuario();

    assertConstraint(
        "ativo_cripto_simbolo_tamanho", ativo(usuario, "bitcoin", null).param("simbolo", ""));
    assertConstraint(
        "ativo_cripto_simbolo_tamanho", ativo(usuario, "bitcoin", null).param("simbolo", " "));
    assertConstraint(
        "ativo_cripto_simbolo_tamanho", ativo(usuario, "bitcoin", null).param("simbolo", " BTC"));
    assertConstraint(
        "ativo_cripto_simbolo_tamanho",
        ativo(usuario, "bitcoin", null).param("simbolo", "A".repeat(21)));
    assertConstraint(
        "ativo_cripto_nome_tamanho",
        ativo(usuario, "bitcoin", null).param("nome", "n".repeat(101)));
    assertConstraint(
        "ativo_cripto_decimais_faixa", ativo(usuario, "bitcoin", null).param("decimais", -1));
    assertConstraint(
        "ativo_cripto_decimais_faixa", ativo(usuario, "bitcoin", null).param("decimais", 19));
    assertThat(
            ativo(usuario, "bitcoin", null)
                .param("simbolo", "A".repeat(20))
                .param("nome", "n".repeat(100))
                .param("decimais", 18)
                .update())
        .isEqualTo(1);
  }

  @Test
  void startsAtivoAsNotConfiavel() {
    UUID usuario = usuario();
    UUID id = ativoId(usuario, "bitcoin", null);

    assertThat(
            jdbc.sql("SELECT confiavel FROM ativo_cripto WHERE id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single())
        .isFalse();
  }

  @Test
  void rejectsRepeatedAtivoButAcceptsItForAnotherUsuario() {
    UUID usuario = usuario();
    ativo(usuario, "base", EVM_CONTRACT).update();
    ativo(usuario, "bitcoin", null).update();

    assertConstraint(
        "ativo_cripto_usuario_rede_contrato_unico", ativo(usuario, "base", EVM_CONTRACT));
    assertConstraint("ativo_cripto_usuario_rede_contrato_unico", ativo(usuario, "bitcoin", null));
    assertThat(ativo(usuario, "ethereum", EVM_CONTRACT).update()).isEqualTo(1);
    assertThat(ativo(usuario(), "base", EVM_CONTRACT).update()).isEqualTo(1);
    assertThat(ativo(usuario(), "bitcoin", null).update()).isEqualTo(1);
  }

  @Test
  void acceptsMovimentoAndDefaultsTheTipoOfConta() {
    Fixture f = fixture();

    assertThat(
            movimento(f.usuario, f.conta, f.ativo, "compra", "0.5", "2026-10-01", "100.00")
                .update())
        .isEqualTo(1);
    assertThat(
            movimento(
                    f.usuario,
                    f.conta,
                    f.ativo,
                    "venda",
                    "0.000000000000000001",
                    "2026-10-02",
                    "0.01")
                .update())
        .isEqualTo(1);
    assertThat(
            movimento(f.usuario, f.conta, f.ativo, "compra", "1", "2026-10-02", "90071992547409.91")
                .update())
        .isEqualTo(1);
  }

  @Test
  void rejectsMovimentoInAContaThatIsNotCripto() {
    Fixture f = fixture();
    UUID corrente = conta(f.usuario, "corrente");

    assertConstraint(
        "movimento_cripto_conta_cripto_do_usuario",
        movimento(f.usuario, corrente, f.ativo, "compra", "1", "2026-10-01", "10.00"));
  }

  @Test
  void rejectsMovimentoWithContaOrAtivoOfAnotherUsuario() {
    Fixture f = fixture();
    Fixture other = fixture();

    assertConstraint(
        "movimento_cripto_conta_cripto_do_usuario",
        movimento(f.usuario, other.conta, f.ativo, "compra", "1", "2026-10-01", "10.00"));
    assertConstraint(
        "movimento_cripto_ativo_do_usuario",
        movimento(f.usuario, f.conta, other.ativo, "compra", "1", "2026-10-01", "10.00"));
  }

  @Test
  void rejectsInvalidMovimentoValues() {
    Fixture f = fixture();

    assertConstraint(
        "movimento_cripto_tipo_valido",
        movimento(f.usuario, f.conta, f.ativo, "troca", "1", "2026-10-01", "10.00"));
    assertConstraint(
        "movimento_cripto_quantidade_positiva",
        movimento(f.usuario, f.conta, f.ativo, "compra", "0", "2026-10-01", "10.00"));
    assertConstraint(
        "movimento_cripto_quantidade_positiva",
        movimento(f.usuario, f.conta, f.ativo, "compra", "-1", "2026-10-01", "10.00"));
    assertConstraint(
        "movimento_cripto_custo_faixa",
        movimento(f.usuario, f.conta, f.ativo, "compra", "1", "2026-10-01", "0"));
    assertConstraint(
        "movimento_cripto_custo_faixa",
        movimento(f.usuario, f.conta, f.ativo, "compra", "1", "2026-10-01", "-5.00"));
    assertConstraint(
        "movimento_cripto_custo_faixa",
        movimento(f.usuario, f.conta, f.ativo, "compra", "1", "2026-10-01", "90071992547409.92"));
    assertConstraint(
        "movimento_cripto_data_faixa",
        movimento(f.usuario, f.conta, f.ativo, "compra", "1", "1899-12-31", "10.00"));
    assertConstraint(
        "movimento_cripto_data_faixa",
        movimento(f.usuario, f.conta, f.ativo, "compra", "1", "2200-01-01", "10.00"));
  }

  @Test
  void rejectsDeletingAContaOrAtivoThatHasMovimento() {
    Fixture f = fixture();
    movimento(f.usuario, f.conta, f.ativo, "compra", "1", "2026-10-01", "10.00").update();

    assertThatThrownBy(
            () -> jdbc.sql("DELETE FROM conta WHERE id = :id").param("id", f.conta).update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.sql("DELETE FROM ativo_cripto WHERE id = :id").param("id", f.ativo).update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void acceptsCotacaoAndRejectsInvalidOnes() {
    Fixture f = fixture();
    Fixture other = fixture();

    assertThat(
            cotacao(f.usuario, f.ativo, "2026-10-01T12:00:00Z", "350000.12345678", "manual")
                .update())
        .isEqualTo(1);
    assertConstraint(
        "cotacao_ativo_do_usuario",
        cotacao(f.usuario, other.ativo, "2026-10-01T12:00:00Z", "1", "manual"));
    assertConstraint(
        "cotacao_preco_positivo",
        cotacao(f.usuario, f.ativo, "2026-10-02T12:00:00Z", "0", "manual"));
    assertConstraint(
        "cotacao_preco_positivo",
        cotacao(f.usuario, f.ativo, "2026-10-02T12:00:00Z", "-1", "manual"));
    // A V16 aceita a fonte automática da F14 (ADR-040); outra fonte continua recusada.
    assertThat(cotacao(f.usuario, f.ativo, "2026-10-02T12:00:00Z", "1", "coingecko").update())
        .isEqualTo(1);
    assertConstraint(
        "cotacao_fonte_valida",
        cotacao(f.usuario, f.ativo, "2026-10-03T12:00:00Z", "1", "binance"));
  }

  @Test
  void rejectsCotacaoAtTheSameMomentAndAcceptsAnotherMomento() {
    Fixture f = fixture();
    cotacao(f.usuario, f.ativo, "2026-10-01T12:00:00Z", "1", "manual").update();

    assertThatThrownBy(
            () -> cotacao(f.usuario, f.ativo, "2026-10-01T12:00:00Z", "2", "manual").update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("cotacao_pkey");
    assertThat(cotacao(f.usuario, f.ativo, "2026-10-01T12:00:01Z", "2", "manual").update())
        .isEqualTo(1);
  }

  @Test
  void deletingTheUsuarioClearsEverything() {
    Fixture f = fixture();
    movimento(f.usuario, f.conta, f.ativo, "compra", "1", "2026-10-01", "10.00").update();
    cotacao(f.usuario, f.ativo, "2026-10-01T12:00:00Z", "1", "manual").update();

    int deleted = jdbc.sql("DELETE FROM usuario WHERE id = :id").param("id", f.usuario).update();

    assertThat(deleted).isEqualTo(1);
    for (String table : new String[] {"conta", "ativo_cripto", "movimento_cripto", "cotacao"}) {
      assertThat(
              jdbc.sql("SELECT count(*) FROM " + table + " WHERE usuario_id = :id")
                  .param("id", f.usuario)
                  .query(Long.class)
                  .single())
          .as(table)
          .isZero();
    }
  }

  private record Fixture(UUID usuario, UUID conta, UUID ativo) {}

  private Fixture fixture() {
    UUID usuario = usuario();
    return new Fixture(usuario, conta(usuario, "cripto"), ativoId(usuario, "ethereum", null));
  }

  private UUID usuario() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", id)
        .param("email", "cripto-" + id + "@example.com")
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
    JdbcClient.StatementSpec spec = contaSpec(usuario, tipo, "0");
    spec.update();
    return jdbc.sql("SELECT id FROM conta WHERE usuario_id = :u AND tipo = :t")
        .param("u", usuario)
        .param("t", tipo)
        .query(UUID.class)
        .single();
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

  private JdbcClient.StatementSpec ativo(UUID usuario, String rede, String contrato) {
    return jdbc.sql(
            "INSERT INTO ativo_cripto (id, usuario_id, rede, contrato, simbolo, nome, decimais)"
                + " VALUES (:id, :usuario, :rede, :contrato, :simbolo, :nome, :decimais)")
        .param("id", UUID.randomUUID())
        .param("usuario", usuario)
        .param("rede", rede)
        .param("contrato", contrato, java.sql.Types.VARCHAR)
        .param("simbolo", "TKN")
        .param("nome", null, java.sql.Types.VARCHAR)
        .param("decimais", 8);
  }

  private UUID ativoId(UUID usuario, String rede, String contrato) {
    ativo(usuario, rede, contrato).update();
    return jdbc.sql(
            "SELECT id FROM ativo_cripto WHERE usuario_id = :u AND rede = :r"
                + " AND contrato IS NOT DISTINCT FROM :c")
        .param("u", usuario)
        .param("r", rede)
        .param("c", contrato, java.sql.Types.VARCHAR)
        .query(UUID.class)
        .single();
  }

  private JdbcClient.StatementSpec movimento(
      UUID usuario,
      UUID conta,
      UUID ativo,
      String tipo,
      String quantidade,
      String data,
      String custo) {
    return jdbc.sql(
            "INSERT INTO movimento_cripto (id, usuario_id, conta_id, ativo_id, tipo, quantidade,"
                + " data, custo, moeda) VALUES (:id, :usuario, :conta, :ativo, :tipo,"
                + " CAST(:quantidade AS numeric), CAST(:data AS date), CAST(:custo AS numeric),"
                + " 'USD')")
        .param("id", UUID.randomUUID())
        .param("usuario", usuario)
        .param("conta", conta)
        .param("ativo", ativo)
        .param("tipo", tipo)
        .param("quantidade", quantidade)
        .param("data", data)
        .param("custo", custo);
  }

  private JdbcClient.StatementSpec cotacao(
      UUID usuario, UUID ativo, String momento, String preco, String fonte) {
    return jdbc.sql(
            "INSERT INTO cotacao (usuario_id, ativo_id, momento, preco, fonte, moeda) VALUES"
                + " (:usuario, :ativo, CAST(:momento AS timestamptz), CAST(:preco AS numeric),"
                + " :fonte, 'USD')")
        .param("usuario", usuario)
        .param("ativo", ativo)
        .param("momento", momento)
        .param("preco", preco)
        .param("fonte", fonte);
  }

  private void assertConstraint(String constraint, JdbcClient.StatementSpec statement) {
    assertThatThrownBy(statement::update)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }
}
