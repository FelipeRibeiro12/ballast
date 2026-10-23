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
class DolarMigrationTest {

  @Autowired JdbcClient jdbc;

  private record Fixture(UUID usuario, UUID conta, UUID ativo) {}

  private Fixture fixture() {
    UUID usuario = UUID.randomUUID();
    UUID conta = UUID.randomUUID();
    UUID ativo = UUID.randomUUID();
    jdbc.sql("INSERT INTO usuario (id, email, senha_hash) VALUES (:id, :email, 'hash')")
        .param("id", usuario)
        .param("email", "dolar-" + usuario + "@example.com")
        .update();
    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :u, 'Exchange', 'cripto')")
        .param("id", conta)
        .param("u", usuario)
        .update();
    jdbc.sql(
            "INSERT INTO ativo_cripto (id, usuario_id, rede, simbolo, decimais)"
                + " VALUES (:id, :u, 'bitcoin', 'BTC', 8)")
        .param("id", ativo)
        .param("u", usuario)
        .update();
    return new Fixture(usuario, conta, ativo);
  }

  private JdbcClient.StatementSpec movimento(Fixture f, String moeda) {
    return jdbc.sql(
            "INSERT INTO movimento_cripto (id, usuario_id, conta_id, ativo_id, tipo, quantidade,"
                + " data, custo, moeda) VALUES (:id, :u, :c, :a, 'compra', 1, DATE '2026-10-01',"
                + " 100.00, :moeda)")
        .param("id", UUID.randomUUID())
        .param("u", f.usuario())
        .param("c", f.conta())
        .param("a", f.ativo())
        .param("moeda", moeda);
  }

  @Test
  void movimentoAndCotacaoTakeBrlOrUsdOnly() {
    Fixture f = fixture();

    assertThat(movimento(f, "USD").update()).isEqualTo(1);
    assertThat(movimento(f, "BRL").update()).isEqualTo(1);
    assertThatThrownBy(() -> movimento(f, "EUR").update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("movimento_cripto_moeda_valida");
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO cotacao (usuario_id, ativo_id, momento, preco, fonte, moeda)"
                            + " VALUES (:u, :a, now(), 1, 'manual', 'EUR')")
                    .param("u", f.usuario())
                    .param("a", f.ativo())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("cotacao_moeda_valida");
  }

  @Test
  void theCurrencyHasNoDefaultSoTheApplicationAlwaysSaysIt() {
    Fixture f = fixture();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO movimento_cripto (id, usuario_id, conta_id, ativo_id, tipo,"
                            + " quantidade, data, custo) VALUES (:id, :u, :c, :a, 'compra', 1,"
                            + " DATE '2026-10-01', 100.00)")
                    .param("id", UUID.randomUUID())
                    .param("u", f.usuario())
                    .param("c", f.conta())
                    .param("a", f.ativo())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void indiceAcceptsTheDollarSeries() {
    jdbc.sql("DELETE FROM indice WHERE serie = 'dolar' AND data = DATE '1952-01-02'").update();

    assertThat(
            jdbc.sql(
                    "INSERT INTO indice (serie, data, valor) VALUES ('dolar', DATE '1952-01-02', 5)")
                .update())
        .isEqualTo(1);
    jdbc.sql("DELETE FROM indice WHERE serie = 'dolar' AND data = DATE '1952-01-02'").update();
  }
}
