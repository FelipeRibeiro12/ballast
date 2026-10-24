package com.felipe.ballast.fixedincome;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// indice é dado público, sem usuario_id (ADR-037). Só o IndexUpdater escreve.
@Repository
class IndexQueries {

  private static final int BATCH_SIZE = 500;

  private final JdbcClient jdbc;
  private final JdbcTemplate template;

  IndexQueries(JdbcClient jdbc, JdbcTemplate template) {
    this.jdbc = jdbc;
    this.template = template;
  }

  // Repetível: o valor que já existe fica, o Banco Central não reescreve índice publicado. Em lote:
  // uma janela de 5 anos do CDI são mais de mil linhas, e um INSERT por ida ao banco pesava.
  void insert(IndexSeries series, List<IndexPoint> points) {
    template.batchUpdate(
        "INSERT INTO indice (serie, data, valor) VALUES (?, ?, ?)"
            + " ON CONFLICT (serie, data) DO NOTHING",
        points,
        BATCH_SIZE,
        (statement, point) -> {
          statement.setString(1, series.value());
          statement.setObject(2, point.date());
          statement.setBigDecimal(3, point.value());
        });
  }

  Optional<LocalDate> first(IndexSeries series) {
    return jdbc.sql("SELECT min(data) FROM indice WHERE serie = :serie")
        .param("serie", series.value())
        .query(LocalDate.class)
        .optional();
  }

  Optional<LocalDate> last(IndexSeries series) {
    return jdbc.sql("SELECT max(data) FROM indice WHERE serie = :serie")
        .param("serie", series.value())
        .query(LocalDate.class)
        .optional();
  }

  NavigableMap<LocalDate, BigDecimal> load(IndexSeries series, LocalDate from) {
    NavigableMap<LocalDate, BigDecimal> values = new TreeMap<>();
    jdbc.sql("SELECT data, valor FROM indice WHERE serie = :serie AND data >= :from ORDER BY data")
        .param("serie", series.value())
        .param("from", from)
        .query(
            (rs, row) ->
                values.put(rs.getObject("data", LocalDate.class), rs.getBigDecimal("valor")))
        .list();
    return values;
  }

  // A primeira aplicação ou o primeiro movimento de cripto de todos os usuários: só a data sai
  // daqui, para o job saber de onde baixar. Nenhum dado de usuário vai para a fonte.
  Optional<LocalDate> earliestApplicationOrMovement() {
    return jdbc.sql(
            "SELECT least((SELECT min(data_aplicacao) FROM titulo_renda_fixa),"
                + " (SELECT min(data) FROM movimento_cripto))")
        .query(LocalDate.class)
        .optional();
  }
}
