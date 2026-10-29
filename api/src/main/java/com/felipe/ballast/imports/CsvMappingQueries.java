package com.felipe.ballast.imports;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017). Toda consulta filtra por usuario_id; mapeamento de outro usuário nunca
// aparece, nem para ler.
@Repository
class CsvMappingQueries {

  record Row(UUID id, String name, CsvMapping mapping) {}

  private static final String COLUMNS =
      """
      SELECT id, nome, separador, formato_data, decimal, sinal, tem_cabecalho, coluna_data,
             coluna_valor, coluna_descricao, coluna_id
      FROM mapeamento_csv
      WHERE usuario_id = :userId
      """;

  private final JdbcClient jdbc;

  CsvMappingQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  List<Row> list(UUID userId) {
    return jdbc.sql(COLUMNS + " ORDER BY criado_em, id")
        .param("userId", userId)
        .query(CsvMappingQueries::mapRow)
        .list();
  }

  Optional<Row> find(UUID userId, UUID id) {
    return jdbc.sql(COLUMNS + " AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .query(CsvMappingQueries::mapRow)
        .optional();
  }

  private static Row mapRow(ResultSet rs, int row) throws SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getString("nome"),
        new CsvMapping(
            CsvMapping.Separator.fromValue(rs.getString("separador")).orElseThrow(),
            CsvMapping.DateFormat.fromValue(rs.getString("formato_data")).orElseThrow(),
            CsvMapping.Decimal.fromValue(rs.getString("decimal")).orElseThrow(),
            CsvMapping.Sign.fromValue(rs.getString("sinal")).orElseThrow(),
            rs.getBoolean("tem_cabecalho"),
            rs.getInt("coluna_data"),
            rs.getInt("coluna_valor"),
            rs.getInt("coluna_descricao"),
            rs.getObject("coluna_id", Integer.class)));
  }

  UUID insert(UUID userId, String name, CsvMapping mapping) {
    UUID id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
    jdbc.sql(
            """
            INSERT INTO mapeamento_csv (id, usuario_id, nome, separador, formato_data, decimal,
                sinal, tem_cabecalho, coluna_data, coluna_valor, coluna_descricao, coluna_id)
            VALUES (:id, :userId, :name, :separator, :dateFormat, :decimal, :sign, :hasHeader,
                :dateColumn, :amountColumn, :descriptionColumn, :idColumn)
            """)
        .param("id", id)
        .param("userId", userId)
        .param("name", name)
        .param("separator", mapping.separator().value())
        .param("dateFormat", mapping.dateFormat().value())
        .param("decimal", mapping.decimal().value())
        .param("sign", mapping.sign().value())
        .param("hasHeader", mapping.hasHeader())
        .param("dateColumn", mapping.dateColumn())
        .param("amountColumn", mapping.amountColumn())
        .param("descriptionColumn", mapping.descriptionColumn())
        .param("idColumn", mapping.idColumn())
        .update();
    return id;
  }

  int update(UUID userId, UUID id, String name, CsvMapping mapping) {
    return jdbc.sql(
            """
            UPDATE mapeamento_csv
            SET nome = :name, separador = :separator, formato_data = :dateFormat,
                decimal = :decimal, sinal = :sign, tem_cabecalho = :hasHeader,
                coluna_data = :dateColumn, coluna_valor = :amountColumn,
                coluna_descricao = :descriptionColumn, coluna_id = :idColumn
            WHERE usuario_id = :userId AND id = :id
            """)
        .param("userId", userId)
        .param("id", id)
        .param("name", name)
        .param("separator", mapping.separator().value())
        .param("dateFormat", mapping.dateFormat().value())
        .param("decimal", mapping.decimal().value())
        .param("sign", mapping.sign().value())
        .param("hasHeader", mapping.hasHeader())
        .param("dateColumn", mapping.dateColumn())
        .param("amountColumn", mapping.amountColumn())
        .param("descriptionColumn", mapping.descriptionColumn())
        .param("idColumn", mapping.idColumn())
        .update();
  }

  int delete(UUID userId, UUID id) {
    return jdbc.sql("DELETE FROM mapeamento_csv WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .update();
  }
}
