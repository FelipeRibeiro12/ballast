package com.felipe.ballast.crypto;

import com.felipe.ballast.fixedincome.FixedIncomeService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Só leitura. Valor e soma em reais saem já na escala de centavos; o controller só troca a escala.
@Service
class NetWorthService {

  // A data do usuário é a de São Paulo: a cotação das 23:30 de lá ainda vale para o dia.
  static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  // 9007199254740991 centavos em reais. Comparado com compareTo: a escala não conta.
  private static final BigDecimal LIMIT = new BigDecimal("90071992547409.91");

  private final CryptoQueries queries;
  private final FixedIncomeService fixedIncome;

  NetWorthService(CryptoQueries queries, FixedIncomeService fixedIncome) {
    this.queries = queries;
    this.fixedIncome = fixedIncome;
  }

  // Valores já em centavos. Nulos sem cotação, e o da outra moeda também nulo sem dólar.
  record ValuedPosition(
      CryptoQueries.Position position,
      BigDecimal valueUsd,
      BigDecimal valueBrl,
      BigDecimal costUsd,
      BigDecimal costBrl) {}

  record Positions(CryptoQueries.Dollar dollar, List<ValuedPosition> positions) {}

  record NetWorth(
      BigDecimal accounts,
      BigDecimal crypto,
      BigDecimal fixedIncome,
      BigDecimal total,
      List<CryptoQueries.Position> unquoted) {}

  // Vazio quando algum valor sai da faixa do JSON. dollar é a PTAX da data (nula se não há).
  Optional<Positions> positions(UUID userId, LocalDate date) {
    CryptoQueries.Dollar dollar = queries.dollarAt(date).orElse(null);
    BigDecimal rate = dollar == null ? null : dollar.value();
    List<ValuedPosition> valued = new ArrayList<>();
    for (CryptoQueries.Position position : queries.positions(userId, date, quoteLimit(date))) {
      BigDecimal usd = null;
      BigDecimal brl = null;
      if (position.price() != null) {
        BigDecimal value = position.quantity().multiply(position.price());
        boolean inUsd = "USD".equals(position.priceCurrency());
        usd = inUsd ? value : convert(value, rate, false);
        brl = inUsd ? convert(value, rate, true) : value;
      }
      ValuedPosition item =
          new ValuedPosition(
              position,
              cents(usd),
              cents(brl),
              cents(position.costUsd()),
              cents(position.costBrl()));
      if (!fits(item.valueUsd())
          || !fits(item.valueBrl())
          || !fits(item.costUsd())
          || !fits(item.costBrl())) {
        return Optional.empty();
      }
      valued.add(item);
    }
    return Optional.of(new Positions(dollar, valued));
  }

  Optional<NetWorth> netWorth(UUID userId, LocalDate date) {
    Optional<Positions> positions = positions(userId, date);
    if (positions.isEmpty()) {
      return Optional.empty();
    }
    // Sem cotação, ou sem o dólar que leva a cotação em USD para reais, a posição fica de fora.
    BigDecimal crypto = BigDecimal.ZERO;
    List<CryptoQueries.Position> unquoted = new ArrayList<>();
    for (ValuedPosition valued : positions.get().positions()) {
      if (valued.valueBrl() == null) {
        unquoted.add(valued.position());
      } else {
        crypto = crypto.add(valued.valueBrl());
      }
    }
    BigDecimal accounts = queries.accountsBalance(userId, date);
    // O líquido de cada título: o que se receberia resgatando (ADR-037).
    Optional<BigDecimal> titles = fixedIncome.netTotal(userId, date);
    if (titles.isEmpty()) {
      return Optional.empty();
    }
    BigDecimal total = accounts.add(crypto).add(titles.get());
    if (!fits(accounts) || !fits(crypto) || !fits(total)) {
      return Optional.empty();
    }
    return Optional.of(new NetWorth(accounts, crypto, titles.get(), total, unquoted));
  }

  // reais = dólares x PTAX; dólares = reais / PTAX. Sem PTAX não há conversão. Fica exato: a
  // divisão
  // usa 34 dígitos, e só cents arredonda.
  private static BigDecimal convert(BigDecimal value, BigDecimal rate, boolean toBrl) {
    if (rate == null) {
      return null;
    }
    return toBrl ? value.multiply(rate) : value.divide(rate, MathContext.DECIMAL128);
  }

  // Arredonda uma vez, aqui, cada moeda a partir do valor cheio. HALF_EVEN porque numa avaliação
  // não há direção certa para o meio centavo, e o arredondamento bancário não acumula viés numa
  // soma de várias posições (ADR-035, ADR-038).
  private static BigDecimal cents(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.HALF_EVEN);
  }

  // A cotação vale até o fim do dia em São Paulo: tudo antes do começo do dia seguinte.
  private static Instant quoteLimit(LocalDate date) {
    return date.plusDays(1).atStartOfDay(ZONE).toInstant();
  }

  private static boolean fits(BigDecimal amount) {
    return amount == null || amount.abs().compareTo(LIMIT) <= 0;
  }
}
