package com.felipe.ballast.imports;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Leitor de tags à mão, sem parser de XML. O OFX 1.x é SGML (tag aberta sem fechamento) e o 2.x é
// XML, mas só o STMTTRN e as filhas interessam, e um leitor de tags serve aos dois. Não expande
// entidade nenhuma, então XXE não tem por onde entrar; DOCTYPE e ENTITY são recusados de saída.
final class OfxParser {

  static final int MAX_TRANSACTIONS = 2000;
  static final int MAX_FIELD_CHARS = 200;

  // Teto do valor da coluna: o mesmo do CHECK lancamento_valor_faixa.
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("90071992547409.91");

  // O banco brasileiro costuma mandar Windows-1252 mesmo declarando outra coisa no cabeçalho.
  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

  // 8 dígitos de data, hora opcional, milissegundos e fuso entre colchetes. A data vale como foi
  // escrita: converter pelo fuso deslocaria a meia-noite do banco para o dia anterior.
  private static final Pattern DATE =
      Pattern.compile("(\\d{4})(\\d{2})(\\d{2})(\\d{0,6})(\\.\\d{1,3})?(\\[[^\\]]*\\])?");
  private static final Pattern PLAIN_NUMBER = Pattern.compile("[+-]?\\d+(\\.\\d+)?");

  private static final String TRANSACTION = "STMTTRN";
  private static final List<String> KNOWN_FIELDS =
      List.of("TRNTYPE", "DTPOSTED", "TRNAMT", "FITID", "NAME", "MEMO", "CHECKNUM");

  private OfxParser() {}

  static OfxFile parse(byte[] content) {
    if (content == null || content.length == 0) {
      throw new OfxException("arquivo vazio");
    }
    String text = decode(content);
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.contains("<!doctype") || lower.contains("<!entity")) {
      throw new OfxException("arquivo com DOCTYPE ou ENTITY não é aceito");
    }
    if (text.indexOf('\0') >= 0 || !lower.contains("<ofx")) {
      throw new OfxException("o arquivo não parece um extrato OFX");
    }
    List<OfxTransaction> found = new ArrayList<>();
    Map<String, String> current = null;
    int position = 0;
    while (true) {
      int open = text.indexOf('<', position);
      int close = open < 0 ? -1 : text.indexOf('>', open);
      if (close < 0) {
        break;
      }
      position = close + 1;
      String tag = text.substring(open + 1, close).trim();
      if (tag.isEmpty() || tag.charAt(0) == '?' || tag.charAt(0) == '!') {
        continue;
      }
      boolean closing = tag.charAt(0) == '/';
      String name = tagName(closing ? tag.substring(1) : tag);
      if (closing) {
        if (current != null && (name.equals(TRANSACTION) || name.equals("BANKTRANLIST"))) {
          add(found, current);
          current = null;
        }
        continue;
      }
      if (name.equals(TRANSACTION)) {
        if (current != null) {
          add(found, current);
        }
        current = new HashMap<>();
        continue;
      }
      if (current != null) {
        int next = text.indexOf('<', position);
        String value = clean(text.substring(position, next < 0 ? text.length() : next));
        current.putIfAbsent(name, value == null ? "" : value);
      }
    }
    if (current != null) {
      add(found, current);
    }
    if (found.isEmpty()) {
      throw new OfxException("nenhuma transação encontrada no arquivo");
    }
    return new OfxFile(List.copyOf(found));
  }

  private static void add(List<OfxTransaction> found, Map<String, String> fields) {
    if (found.size() >= MAX_TRANSACTIONS) {
      throw new OfxException("arquivo com mais de " + MAX_TRANSACTIONS + " transações");
    }
    found.add(toTransaction(found.size() + 1, fields));
  }

  private static OfxTransaction toTransaction(int order, Map<String, String> fields) {
    Map<String, String> known = new LinkedHashMap<>();
    for (String name : KNOWN_FIELDS) {
      if (fields.get(name) != null && !fields.get(name).isEmpty()) {
        known.put(name, fields.get(name));
      }
    }
    LocalDate date = parseDate(fields.get("DTPOSTED"));
    BigDecimal amount = null;
    String reason = null;
    if (date == null) {
      reason = "data inválida";
    } else {
      String raw = fields.get("TRNAMT");
      if (raw == null || raw.isEmpty() || !PLAIN_NUMBER.matcher(normalize(raw)).matches()) {
        reason = "valor inválido";
      } else {
        BigDecimal parsed = new BigDecimal(normalize(raw)).stripTrailingZeros();
        if (parsed.signum() == 0) {
          reason = "valor zero";
        } else if (parsed.scale() > 2) {
          reason = "valor com mais de 2 casas decimais";
        } else if (parsed.abs().compareTo(MAX_AMOUNT) > 0) {
          reason = "valor fora do limite";
        } else {
          amount = parsed.setScale(2);
        }
      }
    }
    return new OfxTransaction(
        order,
        blankToNull(fields.get("TRNTYPE")),
        reason == null ? date : null,
        amount,
        blankToNull(fields.get("FITID")),
        blankToNull(fields.get("NAME")),
        blankToNull(fields.get("MEMO")),
        blankToNull(fields.get("CHECKNUM")),
        reason,
        known);
  }

  // Com os dois separadores, o último é o decimal: "1.234,56" e "1,234.56" valem 1234.56. Com um
  // só, ele é o decimal.
  private static String normalize(String raw) {
    String text = raw.replace(" ", "");
    int comma = text.lastIndexOf(',');
    int dot = text.lastIndexOf('.');
    if (comma >= 0 && dot >= 0) {
      return comma > dot ? text.replace(".", "").replace(',', '.') : text.replace(",", "");
    }
    return text.replace(',', '.');
  }

  private static LocalDate parseDate(String raw) {
    if (raw == null) {
      return null;
    }
    Matcher matcher = DATE.matcher(raw);
    if (!matcher.matches()) {
      return null;
    }
    try {
      int year = Integer.parseInt(matcher.group(1));
      if (year < 1900 || year > 2199) {
        return null;
      }
      return LocalDate.of(
          year, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
    } catch (DateTimeException e) {
      return null;
    }
  }

  private static String tagName(String tag) {
    int end = 0;
    while (end < tag.length() && !Character.isWhitespace(tag.charAt(end))) {
      end++;
    }
    return tag.substring(0, end).toUpperCase(Locale.ROOT);
  }

  // Só as cinco entidades nomeadas do XML; nada é resolvido além delas. Controle vira espaço, e o
  // corte é por code point para não partir um par substituto.
  private static String clean(String raw) {
    String text =
        raw.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&");
    StringBuilder builder = new StringBuilder();
    text.codePoints().forEach(point -> builder.appendCodePoint(isControl(point) ? ' ' : point));
    String stripped = builder.toString().strip();
    if (stripped.isEmpty()) {
      return null;
    }
    if (stripped.codePointCount(0, stripped.length()) > MAX_FIELD_CHARS) {
      return stripped.substring(0, stripped.offsetByCodePoints(0, MAX_FIELD_CHARS)).strip();
    }
    return stripped;
  }

  // Mesmo critério do @NoControlCharacters: o título importado passa pelas mesmas regras do manual.
  private static boolean isControl(int point) {
    int type = Character.getType(point);
    return type == Character.CONTROL
        || (type == Character.FORMAT && point != 0x200C && point != 0x200D)
        || point == 0x2028
        || point == 0x2029;
  }

  private static String blankToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static String decode(byte[] content) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content))
          .toString();
    } catch (CharacterCodingException e) {
      return new String(content, WINDOWS_1252);
    }
  }
}
