package com.felipe.ballast.imports;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Leitor de CSV à mão (RFC 4180), sem dependência: aspas, aspas duplas escapadas, quebra de linha
// dentro de aspas, CRLF, LF e CR. Devolve as mesmas linhas intermediárias do OFX, então o resto do
// fluxo (plano, conciliação, revisão, confirmação) não sabe de que formato vieram.
final class CsvParser {

  static final int MAX_ROWS = 2000;
  static final int MAX_COLUMNS = 50;
  static final int MAX_CELL_CHARS = 200;
  static final int MAX_TITLE_CHARS = 100;

  private static final String SHORT_ROW = "linha com menos colunas que o mapeamento";
  private static final int MIN_YEAR = 1900;
  private static final int MAX_YEAR = 2199;

  private CsvParser() {}

  static CsvFile parse(byte[] content, CsvMapping mapping) {
    if (content == null || content.length == 0) {
      throw new CsvException("arquivo vazio");
    }
    for (byte value : content) {
      if (value == 0) {
        throw new CsvException("o arquivo não parece um CSV de texto");
      }
    }
    String text = OfxParser.decode(content);
    if (!text.isEmpty() && text.charAt(0) == '﻿') {
      text = text.substring(1);
    }
    List<List<String>> records = tokenize(text, mapping);
    int start = mapping.hasHeader() ? 1 : 0;
    if (records.size() <= start) {
      throw new CsvException("nenhuma linha de dados encontrada");
    }
    List<OfxTransaction> rows = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    List<String> descriptionKeys = new ArrayList<>();
    for (int i = start; i < records.size(); i++) {
      read(rows.size() + 1, records.get(i), mapping, rows, ids, descriptionKeys);
    }
    return new CsvFile(List.copyOf(rows), ids, descriptionKeys, records.get(0).size());
  }

  // Os tetos são conferidos enquanto lê: um arquivo de 1 MiB de colunas ou de linhas curtas não
  // chega a virar uma lista enorme. Linha só com células vazias é ignorada e não conta.
  private static List<List<String>> tokenize(String text, CsvMapping mapping) {
    char separator = mapping.separator().character();
    int maxRecords = MAX_ROWS + (mapping.hasHeader() ? 1 : 0);
    List<List<String>> records = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean inQuotes = false;
    boolean atFieldStart = true;
    int length = text.length();
    for (int i = 0; i < length; i++) {
      char current = text.charAt(i);
      if (inQuotes) {
        if (current != '"') {
          field.append(current);
        } else if (i + 1 < length && text.charAt(i + 1) == '"') {
          field.append('"');
          i++;
        } else {
          inQuotes = false;
        }
      } else if (current == '"' && atFieldStart) {
        inQuotes = true;
        atFieldStart = false;
      } else if (current == separator) {
        row.add(field.toString());
        field.setLength(0);
        atFieldStart = true;
        checkColumns(row);
      } else if (current == '\r' || current == '\n') {
        if (current == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
          i++;
        }
        row.add(field.toString());
        field.setLength(0);
        endRecord(records, row, maxRecords);
        row = new ArrayList<>();
        atFieldStart = true;
      } else {
        field.append(current);
        atFieldStart = false;
      }
    }
    if (inQuotes) {
      throw new CsvException("aspas sem fechar no arquivo");
    }
    if (!row.isEmpty() || !atFieldStart) {
      row.add(field.toString());
      endRecord(records, row, maxRecords);
    }
    return records;
  }

  private static void checkColumns(List<String> row) {
    if (row.size() >= MAX_COLUMNS) {
      throw new CsvException("arquivo com mais de " + MAX_COLUMNS + " colunas por linha");
    }
  }

  private static void endRecord(List<List<String>> records, List<String> row, int maxRecords) {
    if (row.size() > MAX_COLUMNS) {
      throw new CsvException("arquivo com mais de " + MAX_COLUMNS + " colunas por linha");
    }
    if (row.stream().allMatch(String::isBlank)) {
      return;
    }
    if (records.size() >= maxRecords) {
      throw new CsvException("arquivo com mais de " + MAX_ROWS + " linhas");
    }
    records.add(row);
  }

  private static void read(
      int order,
      List<String> record,
      CsvMapping mapping,
      List<OfxTransaction> rows,
      List<String> ids,
      List<String> descriptionKeys) {
    Map<String, String> raw = new LinkedHashMap<>();
    for (int i = 0; i < record.size(); i++) {
      raw.put("c" + (i + 1), neutralize(record.get(i)));
    }
    String description = cell(record, mapping.descriptionColumn());
    String descriptionText = description == null ? "" : clean(description);
    String shown = description == null ? "" : neutralize(description);
    String name = null;
    String memo = null;
    if (!shown.isEmpty()) {
      name = cut(shown, MAX_TITLE_CHARS).strip();
      // O texto inteiro só vai para a descrição quando o título precisou ser cortado.
      memo = name.equals(shown) ? null : shown;
    }
    String id = mapping.idColumn() == null ? null : cell(record, mapping.idColumn());
    String idText = id == null || clean(id).isEmpty() ? null : clean(id);

    LocalDate date = null;
    BigDecimal amount = null;
    String reason = null;
    if (record.size() <= mapping.highestColumn()) {
      reason = SHORT_ROW;
    } else {
      date = parseDate(clean(record.get(mapping.dateColumn())), mapping);
      if (date == null) {
        reason = "data inválida";
      } else {
        CsvAmount.Parsed parsed =
            CsvAmount.parse(clean(record.get(mapping.amountColumn())), mapping.decimal());
        if (parsed.error() != null) {
          reason = parsed.error();
        } else {
          amount =
              mapping.sign() == CsvMapping.Sign.POSITIVE_EXPENSE
                  ? parsed.value().negate()
                  : parsed.value();
        }
      }
    }
    boolean valid = reason == null;
    rows.add(
        new OfxTransaction(
            order,
            null,
            valid ? date : null,
            valid ? amount : null,
            null,
            name,
            memo,
            null,
            reason,
            raw));
    ids.add(idText);
    descriptionKeys.add(normalize(descriptionText));
  }

  private static String cell(List<String> record, int index) {
    return index < record.size() ? record.get(index) : null;
  }

  private static LocalDate parseDate(String text, CsvMapping mapping) {
    try {
      LocalDate date = LocalDate.parse(text, mapping.dateFormat().formatter());
      return date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR ? null : date;
    } catch (DateTimeException e) {
      return null;
    }
  }

  // Controle vira espaço (o título passa pelas mesmas regras do lançamento manual), as pontas saem
  // e o corte é por code point para não partir um par substituto.
  private static String clean(String raw) {
    StringBuilder builder = new StringBuilder(raw.length());
    raw.codePoints()
        .forEach(point -> builder.appendCodePoint(OfxParser.isControl(point) ? ' ' : point));
    return cut(builder.toString().strip(), MAX_CELL_CHARS).strip();
  }

  // Fórmula injetada (OWASP): célula que começa com =, +, -, @, TAB ou CR vira texto com um
  // apóstrofo na frente. Vale na gravação, então nenhum lançamento nasce com fórmula crua e uma
  // exportação futura para planilha já sai segura. A coluna de valor e a de data leem a célula
  // crua (clean), não esta: "-1.234,56" é número, não fórmula.
  private static String neutralize(String raw) {
    String cleaned = clean(raw);
    boolean formula =
        !raw.isEmpty() && "=+-@\t\r".indexOf(raw.charAt(0)) >= 0
            || !cleaned.isEmpty() && "=+-@".indexOf(cleaned.charAt(0)) >= 0;
    return formula && !cleaned.isEmpty() ? "'" + cleaned : cleaned;
  }

  // Chave do hash sem id: dois extratos do mesmo banco escrevem a mesma compra com espaço e caixa
  // diferentes. É a célula limpa, antes do apóstrofo.
  private static String normalize(String cleaned) {
    return cleaned.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private static String cut(String text, int maxChars) {
    if (text.codePointCount(0, text.length()) <= maxChars) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, maxChars));
  }
}
