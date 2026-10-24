package com.felipe.ballast.fixedincome;

import com.felipe.ballast.limits.UserLimit;
import com.felipe.ballast.limits.UserLimits;
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
  private static final String MARK_TITLE_FK = "marcacao_renda_fixa_titulo_do_usuario";

  private final FixedIncomeQueries queries;
  private final IndexQueries indices;
  private final IndexUpdater updater;
  private final WriteTransaction transaction;
  private final Clock clock;
  private final UserLimits limits;

  FixedIncomeService(
      FixedIncomeQueries queries,
      IndexQueries indices,
      IndexUpdater updater,
      WriteTransaction transaction,
      Clock clock,
      UserLimits limits) {
    this.limits = limits;
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

  // valuation nulo quando a data é antes da aplicação (reason nulo) ou quando algum valor passa da
  // faixa do JSON (reason diz isso). indexWarning é o texto curto de índice que não cobre o
  // período.
  record Valued(
      FixedIncomeQueries.Title title,
      Valuation valuation,
      String reason,
      boolean matured,
      String indexWarning) {}

  record ValuedList(LocalDate cdiUntil, LocalDate ipcaUntil, List<Valued> titles) {}

  public record Excluded(UUID titleId, String reason) {}

  public record NetTotal(BigDecimal total, List<Excluded> excluded) {}

  static final String OUT_OF_RANGE_REASON = "fora do limite";

  sealed interface SaveResult {
    record Saved(FixedIncomeQueries.Title title) implements SaveResult {}

    enum Rejected implements SaveResult {
      NOT_FOUND,
      ACCOUNT_NOT_FOUND,
      NOT_INVESTMENT,
      ACCOUNT_ARCHIVED,
      APPLICATION_AFTER_REDEMPTION,
      APPLICATION_AFTER_MARK
    }
  }

  enum DatedResult {
    SAVED,
    NOT_FOUND,
    DATE_OUT_OF_RANGE,
    REDEEMED,
    REDEMPTION_BEFORE_MARK
  }

  LocalDate today() {
    return clock.instant().atZone(ZONE).toLocalDate();
  }

  SaveResult create(UUID userId, FixedIncomeQueries.Fields fields) {
    SaveResult result = save(userId, null, fields);
    // Aplicação antiga pede índice que ainda não está guardado. A busca roda em segundo plano: o
    // cadastro não espera o Banco Central, e o valor sai como aplicado até o índice chegar.
    if (result instanceof SaveResult.Saved) {
      updater.requestFrom(fields.applicationDate());
    }
    return result;
  }

  SaveResult update(UUID userId, UUID id, FixedIncomeQueries.Fields fields) {
    SaveResult result = save(userId, id, fields);
    if (result instanceof SaveResult.Saved) {
      updater.requestFrom(fields.applicationDate());
    }
    return result;
  }

  private SaveResult save(UUID userId, UUID id, FixedIncomeQueries.Fields fields) {
    try {
      return transaction.execute(
          status -> {
            if (id == null) {
              limits.checkRoom(userId, UserLimit.FIXED_INCOME_TITLES);
            }
            if (id != null && queries.find(userId, id).isEmpty()) {
              return SaveResult.Rejected.NOT_FOUND;
            }
            // Uma marcação antes da aplicação nova não faria sentido, e o cálculo a ignora.
            if (id != null && queries.hasMarkBefore(userId, id, fields.applicationDate())) {
              return SaveResult.Rejected.APPLICATION_AFTER_MARK;
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
            } else if (queries.update(userId, id, fields) == 0) {
              // Apagado por outra requisição entre a leitura e o UPDATE (QA da F13.2).
              return SaveResult.Rejected.NOT_FOUND;
            }
            return queries
                .find(userId, saved)
                .<SaveResult>map(SaveResult.Saved::new)
                .orElse(SaveResult.Rejected.NOT_FOUND);
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
    try {
      return markLocked(userId, id, date, value);
    } catch (DataIntegrityViolationException e) {
      // O título foi apagado depois da leitura: a FK da marcação recusa (QA da F13.2).
      if (Constraints.isViolated(e, MARK_TITLE_FK)) {
        return DatedResult.NOT_FOUND;
      }
      throw e;
    }
  }

  private DatedResult markLocked(UUID userId, UUID id, LocalDate date, BigDecimal value) {
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

  // Resgatar de novo troca a data e o valor. Marcação depois do resgate ficaria escondida, então o
  // resgate antes de uma marcação é recusado (ADR-058); no mesmo dia vale.
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
          if (queries.hasMarkAfter(userId, id, date)) {
            return DatedResult.REDEMPTION_BEFORE_MARK;
          }
          queries.redeem(userId, id, date, value);
          return DatedResult.SAVED;
        });
  }

  // Idempotente: sem resgate, não muda nada e também vale. Falso só para título que não é do dono.
  boolean undoRedemption(UUID userId, UUID id) {
    Integer updated = transaction.execute(status -> queries.unredeem(userId, id));
    return updated != null && updated > 0;
  }

  Optional<FixedIncomeQueries.Title> find(UUID userId, UUID id) {
    return queries.find(userId, id);
  }

  // Data futura vale o valor de hoje: não há projeção de índice, e o laço de dias úteis fica
  // limitado (auditoria do PR #22). Título com valor fora da faixa do JSON vem sem valor e com o
  // motivo, e não derruba a lista (ADR-058).
  ValuedList list(UUID userId, LocalDate requested) {
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
      FixedIncomeQueries.Mark mark = marks.get(title.id());
      Valuation valuation = evaluate(title, mark, date, cdi, ipca);
      String reason = null;
      if (valuation != null && !fits(valuation)) {
        valuation = null;
        reason = OUT_OF_RANGE_REASON;
      }
      boolean matured =
          title.maturity() != null
              && title.redemptionDate() == null
              && !date.isBefore(title.applicationDate())
              && !date.isBefore(title.maturity());
      String warning =
          valuation == null
              ? null
              : IndexCoverage.warning(title, period(title, mark, date), cdi, ipca);
      valued.add(new Valued(title, valuation, reason, matured, warning));
    }
    return new ValuedList(
        indices.last(IndexSeries.CDI).orElse(null),
        indices.last(IndexSeries.IPCA).orElse(null),
        valued);
  }

  // Soma do líquido de cada título na data, para o patrimônio (ADR-037), sem os que vieram sem
  // valor. Vazio só se a soma dos outros sai da faixa.
  public Optional<NetTotal> netTotal(UUID userId, LocalDate date) {
    ValuedList found = list(userId, date);
    BigDecimal total = BigDecimal.ZERO;
    List<Excluded> excluded = new ArrayList<>();
    for (Valued item : found.titles()) {
      if (item.reason() != null) {
        excluded.add(new Excluded(item.title().id(), item.reason()));
      } else if (item.valuation() != null) {
        total = total.add(item.valuation().net());
      }
    }
    if (total.abs().compareTo(LIMIT) > 0) {
      return Optional.empty();
    }
    return Optional.of(new NetTotal(total, excluded));
  }

  public Optional<LocalDate> cdiUntil() {
    return indices.last(IndexSeries.CDI);
  }

  public Optional<LocalDate> ipcaUntil() {
    return indices.last(IndexSeries.IPCA);
  }

  // De onde e até onde o título rende. Depois do vencimento o valor fica congelado nele
  // (ADR-058). A marcação vale como partida só se cair depois da aplicação.
  record Period(LocalDate start, LocalDate end, BigDecimal startValue) {}

  static Period period(
      FixedIncomeQueries.Title title, FixedIncomeQueries.Mark mark, LocalDate date) {
    boolean fromMark = mark != null && !mark.date().isBefore(title.applicationDate());
    LocalDate end =
        title.maturity() != null && date.isAfter(title.maturity()) ? title.maturity() : date;
    return new Period(
        fromMark ? mark.date() : title.applicationDate(),
        end,
        fromMark ? mark.value() : title.invested());
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
    Period period = period(title, mark, date);
    LocalDate start = period.start();
    LocalDate end = period.end();
    BigDecimal startValue = period.startValue();
    // Marcação depois do vencimento: o banco já disse o valor final, não há o que render.
    BigDecimal factor =
        !end.isAfter(start)
            ? BigDecimal.ONE
            : switch (title.indexer()) {
              case PREFIXED ->
                  FixedIncomeMath.prefixedFactor(
                      title.rate(), FixedIncomeMath.businessDays(cdi, start, end));
              case CDI -> FixedIncomeMath.cdiFactor(cdi, start, end, title.rate());
              case IPCA ->
                  FixedIncomeMath.ipcaFactor(ipca, start, end)
                      .multiply(
                          FixedIncomeMath.prefixedFactor(
                              title.rate(), FixedIncomeMath.businessDays(cdi, start, end)),
                          FixedIncomeMath.CONTEXT);
            };
    // Uma vez, aqui: HALF_EVEN pela mesma razão da cripto (ADR-035, ADR-037).
    BigDecimal gross = startValue.multiply(factor).setScale(2, RoundingMode.HALF_EVEN);
    long days = ChronoUnit.DAYS.between(title.applicationDate(), end);
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
