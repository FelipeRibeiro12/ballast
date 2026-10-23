package com.felipe.ballast.fixedincome;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// indice é dado público, sem usuario_id (ADR-037). Só o IndexUpdater escreve.
@Repository
class IndexQueries {

  private final JdbcClient jdbc;

  IndexQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Repetível: o valor que já existe fica, o Banco Central não reescreve índice publicado.
  void insert(IndexSeries series, List<IndexPoint> points) {
    for (IndexPoint point : points) {
      jdbc.sql(
              "INSERT INTO indice (serie, data, valor) VALUES (:serie, :date, :value)"
                  + " ON CONFLICT (serie, data) DO NOTHING")
          .param("serie", series.value())
          .param("date", point.date())
          .param("value", point.value())
          .update();
    }
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
  Optional<LocalDate> earliestInvestment() {
    return jdbc.sql(
            "SELECT least((SELECT min(data_aplicacao) FROM titulo_renda_fixa),"
                + " (SELECT min(data) FROM movimento_cripto))")
        .query(LocalDate.class)
        .optional();
  }
}
