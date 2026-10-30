package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

// Dado público de mercado, sem usuario_id (ADR-077). Vive em 1953 para não cruzar com outros
// testes.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CotacaoUsdcBrlMigrationTest {

  @Autowired JdbcClient jdbc;

  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM cotacao_usdc_brl WHERE momento < TIMESTAMPTZ '1960-01-01 00:00:00Z'")
        .update();
  }

  private int insert(String price) {
    return jdbc.sql(
            "INSERT INTO cotacao_usdc_brl (momento, preco) VALUES"
                + " (TIMESTAMPTZ '1953-01-02 12:00:00Z', CAST(:p AS numeric))")
        .param("p", price)
        .update();
  }

  @Test
  void storesAPositivePriceWithTheCoingeckoSourceByDefault() {
    assertThat(insert("5.41234567")).isEqualTo(1);

    assertThat(
            jdbc.sql(
                    "SELECT fonte FROM cotacao_usdc_brl"
                        + " WHERE momento = TIMESTAMPTZ '1953-01-02 12:00:00Z'")
                .query(String.class)
                .single())
        .isEqualTo("coingecko");
  }

  @Test
  void rejectsZeroAndNegativePrice() {
    assertThatThrownBy(() -> insert("0"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("cotacao_usdc_brl_preco_check");
    assertThatThrownBy(() -> insert("-1"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("cotacao_usdc_brl_preco_check");
  }

  @Test
  void rejectsTheSameMomentTwice() {
    insert("5");

    assertThatThrownBy(() -> insert("5.1")).isInstanceOf(DataIntegrityViolationException.class);
  }
}
