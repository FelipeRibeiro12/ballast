package com.felipe.ballast.imports;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Heurística por linha: data (dd/MM, dd/MM/yy ou dd/MM/yyyy), descrição e valor no fim (1.234,56,
// com sufixo CR ou sinal de menos para estorno). Layout em duas colunas, valor na linha de baixo
// ou descrição quebrada em duas linhas não são lidos: viram linha para ignorar com o texto cru.
final class GenericStatementReader implements BankStatementReader {

  static final int MAX_LINES = 2000;

  private static final Pattern TRANSACTION =
      Pattern.compile(
          "(\\d{2})/(\\d{2})(?:/(\\d{4}|\\d{2}))?\\s+(.+?)\\s+"
              + "((?:R\\$\\s*)?-?\\s*\\d{1,3}(?:\\.\\d{3})*,\\d{2}|(?:R\\$\\s*)?-?\\s*\\d+,\\d{2})"
              + "\\s*(CR)?");
  private static final Pattern FULL_DATE = Pattern.compile("\\b(\\d{2})/(\\d{2})/(\\d{4})\\b");
  private static final Pattern INSTALLMENT =
      Pattern.compile(
          "(?:^|\\s)(?:PARC(?:ELA)?\\.?\\s*)?(\\d{1,2})/(\\d{1,2})$", Pattern.CASE_INSENSITIVE);
  // Linha que parece lançamento mas não casou: começa com data ou traz um valor com centavos.
  private static final Pattern LOOKS_LIKE_ENTRY =
      Pattern.compile("^\\d{2}/\\d{2}\\b.*|.*\\d,\\d{2}(\\s*CR)?$", Pattern.CASE_INSENSITIVE);
  // Crédito de pagamento da fatura: virar receita no cartão contaria o pagamento duas vezes, porque
  // a transferência da conta de pagamento já existe.
  private static final Pattern PAYMENT =
      Pattern.compile("pagamento|pagto|pgto|pag\\.? fatura", Pattern.CASE_INSENSITIVE);

  private static final int MIN_YEAR = 1900;
  private static final int MAX_YEAR = 2199;
  private static final int MAX_INSTALLMENTS = 48;
  // Data cheia depois disso não é referência da fatura (próxima fatura, validade do cartão).
  private static final int FUTURE_REFERENCE_DAYS = 60;

  @Override
  public boolean recognizes(String text) {
    return true;
  }

  @Override
  public PdfFile read(String text, LocalDate today) {
    LocalDate reference = referenceDate(text, today);
    List<OfxTransaction> rows = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    for (String rawLine : text.split("\\R")) {
      String line = CsvParser.clean(rawLine);
      if (line.isEmpty()) {
        continue;
      }
      Matcher matcher = TRANSACTION.matcher(line);
      if (matcher.matches()) {
        if (rows.size() >= MAX_LINES) {
          throw new PdfException("fatura com mais de " + MAX_LINES + " linhas");
        }
        rows.add(transaction(rows.size() + 1, line, matcher, reference, keys));
      } else if (LOOKS_LIKE_ENTRY.matcher(line).matches()) {
        if (rows.size() >= MAX_LINES) {
          throw new PdfException("fatura com mais de " + MAX_LINES + " linhas");
        }
        rows.add(unrecognized(rows.size() + 1, line, "linha não reconhecida"));
        keys.add("");
      }
    }
    return new PdfFile(List.copyOf(rows), keys);
  }

  private static OfxTransaction transaction(
      int order, String line, Matcher matcher, LocalDate reference, List<String> keys) {
    String description = matcher.group(4).strip();
    keys.add(CsvParser.normalize(description));
    boolean credit = matcher.group(6) != null;
    CsvAmount.Parsed parsed = CsvAmount.parse(matcher.group(5), CsvMapping.Decimal.COMMA);
    LocalDate date = resolveDate(matcher, reference);
    String reason = null;
    BigDecimal amount = null;
    if (date == null) {
      reason = "data inválida";
    } else if (parsed.error() != null) {
      reason = parsed.error();
    } else {
      // Na fatura a compra vem positiva; CR ou sinal de menos é crédito (estorno). No formato
      // intermediário o valor tem sinal: saída negativa, entrada positiva.
      credit = credit || parsed.value().signum() < 0;
      amount = credit ? parsed.value().abs() : parsed.value().abs().negate();
      if (credit && PAYMENT.matcher(description).find()) {
        reason = "pagamento da fatura: registre como transferência";
      }
    }
    Map<String, String> raw = new LinkedHashMap<>();
    raw.put("texto", CsvParser.neutralize(line));
    Matcher installment = INSTALLMENT.matcher(description);
    if (installment.find()) {
      int number = Integer.parseInt(installment.group(1));
      int total = Integer.parseInt(installment.group(2));
      if (total >= 2 && total <= MAX_INSTALLMENTS && number >= 1 && number <= total) {
        raw.put("parcela", number + "/" + total);
      }
    }
    String shown = CsvParser.neutralize(description);
    String name = CsvParser.cut(shown, CsvParser.MAX_TITLE_CHARS).strip();
    String memo = name.equals(shown) ? null : shown;
    boolean valid = reason == null;
    return new OfxTransaction(
        order,
        null,
        valid ? date : null,
        valid ? amount : null,
        null,
        name,
        memo,
        null,
        reason,
        raw);
  }

  private static OfxTransaction unrecognized(int order, String line, String reason) {
    String shown = CsvParser.neutralize(line);
    return new OfxTransaction(
        order,
        null,
        null,
        null,
        null,
        CsvParser.cut(shown, CsvParser.MAX_TITLE_CHARS).strip(),
        null,
        null,
        reason,
        new LinkedHashMap<>(Map.of("texto", shown)));
  }

  // dd/MM sem ano: o ano da referência, ou o anterior se a data cair depois dela (compra de
  // dezembro numa fatura que vence em janeiro).
  private static LocalDate resolveDate(Matcher matcher, LocalDate reference) {
    int day = Integer.parseInt(matcher.group(1));
    int month = Integer.parseInt(matcher.group(2));
    String year = matcher.group(3);
    if (year != null) {
      int value = year.length() == 2 ? 2000 + Integer.parseInt(year) : Integer.parseInt(year);
      LocalDate date = safeDate(value, month, day);
      return date == null || date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR ? null : date;
    }
    for (int candidate = reference.getYear(); candidate >= reference.getYear() - 1; candidate--) {
      LocalDate date = safeDate(candidate, month, day);
      if (date != null && !date.isAfter(reference)) {
        return date;
      }
    }
    return null;
  }

  // A maior data cheia do texto (o vencimento, em geral), desde que não seja futura demais.
  private static LocalDate referenceDate(String text, LocalDate today) {
    LocalDate limit = today.plusDays(FUTURE_REFERENCE_DAYS);
    LocalDate best = null;
    Matcher matcher = FULL_DATE.matcher(text);
    while (matcher.find()) {
      LocalDate date =
          safeDate(
              Integer.parseInt(matcher.group(3)),
              Integer.parseInt(matcher.group(2)),
              Integer.parseInt(matcher.group(1)));
      if (date != null
          && date.getYear() >= MIN_YEAR
          && !date.isAfter(limit)
          && (best == null || date.isAfter(best))) {
        best = date;
      }
    }
    return best == null ? today : best;
  }

  private static LocalDate safeDate(int year, int month, int day) {
    try {
      return LocalDate.of(year, month, day);
    } catch (DateTimeException e) {
      return null;
    }
  }
}
