package com.felipe.ballast.crypto;

import com.felipe.ballast.fixedincome.FixedIncomeService;
import java.math.BigDecimal;
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

  // value é nulo sem cotação.
  record ValuedPosition(CryptoQueries.Position position, BigDecimal value) {}

  record NetWorth(
      BigDecimal accounts,
      BigDecimal crypto,
      BigDecimal fixedIncome,
      BigDecimal total,
      List<CryptoQueries.Position> unquoted) {}

  // Vazio quando algum valor sai da faixa do JSON.
  Optional<List<ValuedPosition>> positions(UUID userId, LocalDate date) {
    List<ValuedPosition> valued = new ArrayList<>();
    for (CryptoQueries.Position position : queries.positions(userId, date, quoteLimit(date))) {
      BigDecimal value = position.price() == null ? null : valueOf(position);
      if (!fits(position.cost()) || (value != null && !fits(value))) {
        return Optional.empty();
      }
      valued.add(new ValuedPosition(position, value));
    }
    return Optional.of(valued);
  }

  Optional<NetWorth> netWorth(UUID userId, LocalDate date) {
    Optional<List<ValuedPosition>> positions = positions(userId, date);
    if (positions.isEmpty()) {
      return Optional.empty();
    }
    BigDecimal crypto = BigDecimal.ZERO;
    List<CryptoQueries.Position> unquoted = new ArrayList<>();
    for (ValuedPosition valued : positions.get()) {
      if (valued.value() == null) {
        unquoted.add(valued.position());
      } else {
        crypto = crypto.add(valued.value());
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

  // Arredonda uma vez, aqui, por posição: o valor de cada linha é o que a tela mostra, e o total
  // é a soma delas. HALF_EVEN porque numa avaliação não há direção certa para o meio centavo, e o
  // arredondamento bancário não acumula viés numa soma de várias posições (ADR-035).
  private static BigDecimal valueOf(CryptoQueries.Position position) {
    return position.quantity().multiply(position.price()).setScale(2, RoundingMode.HALF_EVEN);
  }

  // A cotação vale até o fim do dia em São Paulo: tudo antes do começo do dia seguinte.
  private static Instant quoteLimit(LocalDate date) {
    return date.plusDays(1).atStartOfDay(ZONE).toInstant();
  }

  private static boolean fits(BigDecimal amount) {
    return amount.abs().compareTo(LIMIT) <= 0;
  }
}
