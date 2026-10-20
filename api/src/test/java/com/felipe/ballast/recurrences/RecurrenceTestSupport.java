package com.felipe.ballast.recurrences;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

// Leituras diretas no banco: o teste confere o que foi gravado sem passar pela API que está
// sendo testada.
final class RecurrenceTestSupport {

  private final JdbcClient jdbc;

  RecurrenceTestSupport(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  List<LocalDate> occurrenceDates(String templateId) {
    return jdbc.sql(
            "SELECT ocorrencia_data FROM lancamento WHERE template_id = :id"
                + " ORDER BY ocorrencia_data")
        .param("id", UUID.fromString(templateId))
        .query(LocalDate.class)
        .list();
  }

  long countOccurrences(String templateId) {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE template_id = :id")
        .param("id", UUID.fromString(templateId))
        .query(Long.class)
        .single();
  }

  long countTemplates(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM template_lancamento WHERE usuario_id = :id")
        .param("id", userId)
        .query(Long.class)
        .single();
  }

  long countRules(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM regra_recorrencia WHERE usuario_id = :id")
        .param("id", userId)
        .query(Long.class)
        .single();
  }

  long countEntries(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :id")
        .param("id", userId)
        .query(Long.class)
        .single();
  }

  // Linha prevista escrita à mão, para montar o que a API sozinha não produz (data passada,
  // desgarrada, realizada).
  void insertOccurrence(String templateId, LocalDate date, String status, boolean detached) {
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, descricao, valor, categoria_id,
                                    conta_id, data_competencia, data_caixa, status, template_id,
                                    ocorrencia_data, desgarrado)
            SELECT :id, t.usuario_id, t.tipo, t.titulo, t.descricao, t.valor_total, t.categoria_id,
                   t.conta_id, :date, :date, :status, t.id, :date, :detached
            FROM template_lancamento t WHERE t.id = :templateId
            """)
        .param("id", UUID.randomUUID())
        .param("date", date)
        .param("status", status)
        .param("detached", detached)
        .param("templateId", UUID.fromString(templateId))
        .update();
  }

  void deleteOccurrence(String templateId, LocalDate date) {
    jdbc.sql("DELETE FROM lancamento WHERE template_id = :id AND ocorrencia_data = :date")
        .param("id", UUID.fromString(templateId))
        .param("date", date)
        .update();
  }

  record Row(
      String id,
      String status,
      boolean detached,
      LocalDate occurrenceDate,
      LocalDate cashDate,
      String title) {}

  // A ocorrência pela chave da regra (a data que a regra gerou), mesmo depois de editada.
  java.util.Optional<Row> byKey(String templateId, LocalDate key) {
    return jdbc.sql(
            "SELECT id, status, desgarrado, ocorrencia_data, data_caixa, titulo FROM lancamento"
                + " WHERE template_id = :id AND ocorrencia_data = :key")
        .param("id", UUID.fromString(templateId))
        .param("key", key)
        .query(
            (rs, row) ->
                new Row(
                    rs.getString("id"),
                    rs.getString("status"),
                    rs.getBoolean("desgarrado"),
                    rs.getObject("ocorrencia_data", LocalDate.class),
                    rs.getObject("data_caixa", LocalDate.class),
                    rs.getString("titulo")))
        .optional();
  }

  long countByStatus(String templateId, String status) {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE template_id = :id AND status = :status")
        .param("id", UUID.fromString(templateId))
        .param("status", status)
        .query(Long.class)
        .single();
  }
}
