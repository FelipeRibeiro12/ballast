package com.felipe.ballast.fixedincome;

import com.felipe.ballast.web.Constraints;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// Títulos de renda fixa e o valor deles numa data (ADR-037). Assume entrada validada na borda;
// todo método recebe o dono vindo da sessão.
@Service
public class FixedIncomeService {

  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  // 9007199254740991 centavos em reais. Comparado com compareTo: a escala não conta.
  private static final BigDecimal LIMIT = new BigDecimal("90071992547409.91");

  private static final String ACCOUNT_FK = "titulo_renda_fixa_conta_investimento_do_usuario";
  private static final String REDEMPTION_AFTER = "titulo_renda_fixa_resgate_depois";

  private final FixedIncomeQueries queries;
  private final IndexQueries indices;
  private final IndexUpdater updater;
  private final WriteTransaction transaction;
  private final Clock clock;

  FixedIncomeService(
      FixedIncomeQueries queries,
      IndexQueries indices,
      IndexUpdater updater,
      WriteTransaction transaction,
      Clock clock) {
    this.queries = queries;
    this.indices = indices;
    this.updater = updater;
    this.transaction = transaction;
    this.clock = clock;
  }

  // Valor bruto e impostos estimados, já em centavos. gain é o rendimento: bruto menos aplicado, ou
  // resgate menos aplicado depois do resgate.
  record Valuation(
      BigDecimal gross, BigDecimal iof, BigDecimal incomeTax, BigDecimal net, BigDecimal gain) {}

  record Valued(FixedIncomeQueries.Title title, Valuation valuation) {}

  record ValuedList(LocalDate cdiUntil, LocalDate ipcaUntil, List<Valued> titles) {}

  sealed interface SaveResult {
    record Saved(FixedIncomeQueries.Title title) implements SaveResult {}

    enum Rejected implements SaveResult {
      NOT_FOUND,
      ACCOUNT_NOT_FOUND,
      NOT_INVESTMENT,
      ACCOUNT_ARCHIVED,
      APPLICATION_AFTER_REDEMPTION
    }
  }

  enum DatedResult {
    SAVED,
    NOT_FOUND,
    DATE_OUT_OF_RANGE,
    REDEEMED
  }

  LocalDate today() {
    return clock.instant().atZone(ZONE).toLocalDate();
  }

  SaveResult create(UUID userId, FixedIncomeQueries.Fields fields) {
    SaveResult result = save(userId, null, fields);
    // Fora da transação: aplicação antiga pede índice que ainda não está guardado. A fonte fora do
    // ar não atrapalha o cadastro, só deixa o valor como aplicado até a próxima rodada do job.
    if (result instanceof SaveResult.Saved) {
      updater.updateFrom(fields.applicationDate());
    }
    return result;
  }

  SaveResult update(UUID userId, UUID id, FixedIncomeQueries.Fields fields) {
    SaveResult result = save(userId, id, fields);
    if (result instanceof SaveResult.Saved) {
      updater.updateFrom(fields.applicationDate());
    }
    return result;
  }

  private SaveResult save(UUID userId, UUID id, FixedIncomeQueries.Fields fields) {
    try {
      return transaction.execute(
          status -> {
            if (id != null && queries.find(userId, id).isEmpty()) {
              return SaveResult.Rejected.NOT_FOUND;
            }
            Optional<FixedIncomeQueries.Account> account =
                fields.accountId() == null
                    ? Optional.empty()
                    : queries.findAccount(userId, fields.accountId());
            if (account.isEmpty()) {
              return SaveResult.Rejected.ACCOUNT_NOT_FOUND;
            }
            if (!account.get().investment()) {
              return SaveResult.Rejected.NOT_INVESTMENT;
            }
            if (account.get().archived()) {
              return SaveResult.Rejected.ACCOUNT_ARCHIVED;
            }
            UUID saved = id;
            if (id == null) {
              saved = queries.insert(userId, fields);
            } else {
              queries.update(userId, id, fields);
            }
            return new SaveResult.Saved(queries.find(userId, saved).orElseThrow());
          });
    } catch (DataIntegrityViolationException e) {
      if (Constraints.isViolated(e, ACCOUNT_FK)) {
        return SaveResult.Rejected.ACCOUNT_NOT_FOUND;
      }
      if (Constraints.isViolated(e, REDEMPTION_AFTER)) {
        return SaveResult.Rejected.APPLICATION_AFTER_REDEMPTION;
      }
      throw e;
    }
  }

  boolean delete(UUID userId, UUID id) {
    Integer deleted = transaction.execute(status -> queries.delete(userId, id));
    return deleted != null && deleted > 0;
  }

  // Marcação entre a aplicação e hoje, e não depois do resgate.
  DatedResult mark(UUID userId, UUID id, LocalDate date, BigDecimal value) {
    return transaction.execute(
        status -> {
          Optional<FixedIncomeQueries.Title> title = queries.find(userId, id);
          if (title.isEmpty()) {
            return DatedResult.NOT_FOUND;
          }
          if (outOfRange(title.get(), date)) {
            return DatedResult.DATE_OUT_OF_RANGE;
          }
          if (title.get().redemptionDate() != null
              && !date.isBefore(title.get().redemptionDate())) {
            return DatedResult.REDEEMED;
          }
          queries.upsertMark(userId, id, date, value);
          return DatedResult.SAVED;
        });
  }

  // Resgatar de novo troca a data e o valor.
  DatedResult redeem(UUID userId, UUID id, LocalDate date, BigDecimal value) {
    return transaction.execute(
        status -> {
          Optional<FixedIncomeQueries.Title> title = queries.find(userId, id);
          if (title.isEmpty()) {
            return DatedResult.NOT_FOUND;
          }
          if (outOfRange(title.get(), date)) {
            return DatedResult.DATE_OUT_OF_RANGE;
          }
          queries.redeem(userId, id, date, value);
          return DatedResult.SAVED;
        });
  }

  Optional<FixedIncomeQueries.Title> find(UUID userId, UUID id) {
    return queries.find(userId, id);
  }

  // Vazio quando algum valor sai da faixa do JSON. Data futura vale o valor de hoje: não há
  // projeção
  // de índice, e o laço de dias úteis fica limitado (auditoria do PR #22).
  Optional<ValuedList> list(UUID userId, LocalDate requested) {
    LocalDate today = today();
    LocalDate date = requested.isAfter(today) ? today : requested;
    List<FixedIncomeQueries.Title> titles = queries.list(userId);
    Map<UUID, FixedIncomeQueries.Mark> marks = queries.marksUpTo(userId, date);
    LocalDate from =
        titles.stream()
            .map(FixedIncomeQueries.Title::applicationDate)
            .min(LocalDate::compareTo)
            .orElse(date);
    NavigableMap<LocalDate, BigDecimal> cdi = indices.load(IndexSeries.CDI, from);
    NavigableMap<LocalDate, BigDecimal> ipca =
        indices.load(IndexSeries.IPCA, from.withDayOfMonth(1).minusYears(1));
    List<Valued> valued = new ArrayList<>();
    for (FixedIncomeQueries.Title title : titles) {
      Valuation valuation = evaluate(title, marks.get(title.id()), date, cdi, ipca);
      if (valuation != null && !fits(valuation)) {
        return Optional.empty();
      }
      valued.add(new Valued(title, valuation));
    }
    return Optional.of(
        new ValuedList(
            indices.last(IndexSeries.CDI).orElse(null),
            indices.last(IndexSeries.IPCA).orElse(null),
            valued));
  }

  // Soma do líquido de cada título na data, para o patrimônio (ADR-037). Vazio fora da faixa.
  public Optional<BigDecimal> netTotal(UUID userId, LocalDate date) {
    return list(userId, date)
        .map(
            found ->
                found.titles().stream()
                    .map(Valued::valuation)
                    .filter(Objects::nonNull)
                    .map(Valuation::net)
                    .reduce(BigDecimal.ZERO, BigDecimal::add))
        .filter(total -> total.abs().compareTo(LIMIT) <= 0);
  }

  public Optional<LocalDate> cdiUntil() {
    return indices.last(IndexSeries.CDI);
  }

  public Optional<LocalDate> ipcaUntil() {
    return indices.last(IndexSeries.IPCA);
  }

  // Nulo antes da aplicação. Depois do resgate vale zero: o dinheiro saiu do título.
  private static Valuation evaluate(
      FixedIncomeQueries.Title title,
      FixedIncomeQueries.Mark mark,
      LocalDate date,
      NavigableMap<LocalDate, BigDecimal> cdi,
      NavigableMap<LocalDate, BigDecimal> ipca) {
    if (date.isBefore(title.applicationDate())) {
      return null;
    }
    if (title.redemptionDate() != null && !date.isBefore(title.redemptionDate())) {
      return new Valuation(
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          title.redemptionValue().subtract(title.invested()));
    }
    boolean fromMark = mark != null && !mark.date().isBefore(title.applicationDate());
    LocalDate start = fromMark ? mark.date() : title.applicationDate();
    BigDecimal startValue = fromMark ? mark.value() : title.invested();
    BigDecimal factor =
        switch (title.indexer()) {
          case PREFIXED ->
              FixedIncomeMath.prefixedFactor(
                  title.rate(), FixedIncomeMath.businessDays(cdi, start, date));
          case CDI -> FixedIncomeMath.cdiFactor(cdi, start, date, title.rate());
          case IPCA ->
              FixedIncomeMath.ipcaFactor(ipca, start, date)
                  .multiply(
                      FixedIncomeMath.prefixedFactor(
                          title.rate(), FixedIncomeMath.businessDays(cdi, start, date)),
                      FixedIncomeMath.CONTEXT);
        };
    // Uma vez, aqui: HALF_EVEN pela mesma razão da cripto (ADR-035, ADR-037).
    BigDecimal gross = startValue.multiply(factor).setScale(2, RoundingMode.HALF_EVEN);
    long days = ChronoUnit.DAYS.between(title.applicationDate(), date);
    FixedIncomeMath.Taxes taxes =
        FixedIncomeMath.taxes(title.type(), title.invested(), gross, days);
    return new Valuation(
        gross, taxes.iof(), taxes.incomeTax(), taxes.net(), gross.subtract(title.invested()));
  }

  private boolean outOfRange(FixedIncomeQueries.Title title, LocalDate date) {
    return date.isBefore(title.applicationDate()) || date.isAfter(today());
  }

  private static boolean fits(Valuation valuation) {
    return valuation.gross().abs().compareTo(LIMIT) <= 0
        && valuation.net().abs().compareTo(LIMIT) <= 0
        && valuation.gain().abs().compareTo(LIMIT) <= 0;
  }
}
